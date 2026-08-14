-- Whaaack!: which columns a client may write, and a signup trigger that cannot take an
-- account down with it.
--
-- The previous hardening pass closed what a client could *read*. This one closes what it can
-- *write*. RLS answers "which rows"; it says nothing about "which columns", and Supabase's
-- default grants hand `authenticated` every column of every table in public — so two rules
-- that look enforced were resting on the client's good manners, and a third could refuse to
-- create an account at all.

-- ------------------------------------------------------------- scores: writable columns

-- `created_at` had a `default now()` and nothing else standing behind it. Both leaderboards
-- order by `millis desc, created_at asc`, so a POST carrying "created_at":"1970-01-01T00:00:00Z"
-- — the app's own submit payload with one extra key — wins every tie for ever. And the weekly
-- board filters on `created_at >= current_week_start()`, so a far-future value pins a score to
-- it permanently.
--
-- A column-level REVOKE cannot subtract from a table-level GRANT: Postgres keeps the two
-- separate and the table grant goes on implying every column. So the table privilege has to be
-- dropped first and the columns granted back explicitly.
revoke insert, update, delete on public.scores from anon, authenticated;

-- The client sends exactly these — in practice only the last three, letting `default auth.uid()`
-- fill user_id (see LeaderboardRepository.submit). `id` is `generated always as identity` and
-- refuses an explicit value anyway; `created_at` is now reachable only through its default.
grant insert (user_id, millis, hits, top_speed) on public.scores to authenticated;

-- There is no UPDATE and no DELETE policy, so RLS already denied both. Leaving the grants in
-- place made that read as an oversight rather than a decision; the revoke above says it out loud.

-- ----------------------------------------------------------- profiles: writable columns

-- The UPDATE policy authorises the whole row, and the cooldown trigger reads
-- `old.display_name_changed_at` — so two requests defeated the 30-day rename rule. First
-- PATCH {"display_name_changed_at": null}: display_name is unchanged, so the trigger's `if`
-- never fires and the null is written. Then PATCH the new name, which now compares against a
-- null old value and passes. The same grant let a player set `provider` to 'google' — which
-- drives `Player.isGoogle` and therefore which credential controls Settings offers — and
-- rewrite `created_at`.
revoke insert, update, delete on public.profiles from anon, authenticated;

-- display_name is the only field a player may edit. `display_name_changed_at` is stamped by
-- the BEFORE UPDATE trigger, and a trigger's writes to NEW are not checked against the
-- caller's column privileges — only the columns named in the statement are.
grant update (display_name) on public.profiles to authenticated;

-- Profiles are created by the signup trigger and removed by the cascade from auth.users.
-- Neither an INSERT nor a DELETE policy exists, so both were already denied; same reasoning
-- as above for saying so explicitly.

-- ----------------------------------------------------------------------- search_path

-- Every SECURITY DEFINER function was already pinned. These two are not, so neither is a
-- privilege-escalation path today — but `current_week_start()` is called from inside one that
-- is, which is exactly the shape that becomes a real vulnerability the day somebody adds
-- `security definer` without noticing. Supabase's Security Advisor flags both as
-- `function_search_path_mutable`. The rule worth keeping: every function in public is pinned,
-- as a review rule rather than a per-function judgement.
alter function public.enforce_display_name_cooldown() set search_path = public, pg_temp;
alter function public.current_week_start() set search_path = public, pg_temp;

-- ------------------------------------------------------------------ signup provisioning

-- handle_new_user() runs AFTER INSERT on auth.users, so anything it raises rolls the account
-- back and signup answers 500 — the account simply cannot be created. It had three ways to do
-- that, and one of them fires on ordinary names:
--
--   1. The sanitiser stripped characters outside [A-Za-z0-9 ._-], but `display_name_shape`
--      also demands the first *and last* characters be alphanumeric. A Google account named
--      `-bartek-`, `_dev`, `x.` or `._.` therefore produced a candidate the constraint
--      rejected. Truncating a long name to 24 could land on a `.` or `-` and do the same.
--   2. `while exists (...)` followed by INSERT is check-then-act: two signups resolving to the
--      same name both clear the loop and one hits profiles_display_name_key with 23505.
--   3. A null `email` on a provider that sends no name made `split_part(null, ...)` null, and
--      the null flowed all the way to a not-null violation on display_name.
--
-- The rule this function now follows: it may fail to pick a *pretty* name, but it may never
-- fail to create one. An odd display name is recoverable in Settings; a blocked signup is not.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    candidate text;
    base      text;
    fallback  text;
    conflict  text;
    suffix    int := 1;
    provider  text;
begin
    provider := coalesce(new.raw_app_meta_data ->> 'provider', 'email');

    -- Google hands us the account name; email signups send their own display_name.
    candidate := coalesce(
        nullif(trim(new.raw_user_meta_data ->> 'display_name'), ''),
        nullif(trim(new.raw_user_meta_data ->> 'full_name'), ''),
        nullif(trim(new.raw_user_meta_data ->> 'name'), ''),
        split_part(coalesce(new.email, ''), '@', 1)
    );

    -- Squeeze it into the shape the check constraint accepts. Truncate to 24 *before* trimming
    -- the edges, so a cut that lands on punctuation is cleaned up by the same pass.
    candidate := regexp_replace(coalesce(candidate, ''), '[^A-Za-z0-9 ._-]', '', 'g');
    candidate := left(trim(candidate), 24);
    candidate := regexp_replace(candidate, '^[^A-Za-z0-9]+|[^A-Za-z0-9]+$', '', 'g');
    if char_length(candidate) < 2 then
        candidate := 'Player';
    end if;
    base := candidate;

    -- Always available and always valid: 'Player ' plus twelve hex digits is 19 characters,
    -- inside the 2..24 length rule, starting on a letter and ending on a hex digit. Twelve
    -- rather than eight because this is the branch with nothing left to fall back to, and the
    -- extra four digits cost nothing against a name the player can change in Settings anyway.
    fallback := 'Player ' || left(replace(new.id::text, '-', ''), 12);

    -- Try-and-catch rather than look-then-leap, so a concurrent signup that takes the name
    -- between the check and the insert costs a retry instead of costing the account.
    for _attempt in 1..12 loop
        begin
            insert into public.profiles (id, display_name, provider)
            values (new.id, candidate, provider);
            return new;

        exception
            when unique_violation then
                -- CONSTRAINT_NAME carries the index name for a plain unique index too, so
                -- this distinguishes profiles_display_name_key from profiles_pkey.
                get stacked diagnostics conflict = constraint_name;
                if conflict = 'profiles_pkey' then
                    -- Already provisioned. Nothing to do, and nothing to fail over.
                    return new;
                end if;
                -- The name is taken. Make room for the suffix, then re-trim the trailing edge
                -- because that cut can land on punctuation too.
                suffix := suffix + 1;
                candidate := regexp_replace(
                                 left(base, 23 - char_length(suffix::text)),
                                 '[^A-Za-z0-9]+$', '', 'g'
                             ) || ' ' || suffix::text;
                if char_length(candidate) < 2 then
                    candidate := fallback;
                end if;

            when others then
                -- A constraint this function did not anticipate, or a trigger added later.
                -- Whatever it is, it must not take the account with it.
                raise warning 'handle_new_user: % (%) — falling back', sqlerrm, sqlstate;
                candidate := fallback;
        end;
    end loop;

    -- Twelve collisions on the chosen name. Take the uuid-derived one; if even that fails,
    -- leave the account without a profile rather than leaving the player without an account.
    begin
        insert into public.profiles (id, display_name, provider)
        values (new.id, fallback, provider);
    exception when others then
        raise warning 'handle_new_user: no profile for % — %', new.id, sqlerrm;
    end;

    return new;
end;
$$;

comment on function public.handle_new_user is
    'Signup trigger. Derives a valid, unique display name and never raises: a blocked signup '
    'is unrecoverable, an odd display name is not.';

-- ---------------------------------------------------------------------------- verifying
--
-- Signed in as a test player, against the live REST API:
--
--   PATCH /rest/v1/scores            -> 403 / 42501 (was 204)
--   POST  /rest/v1/scores  with created_at
--                                    -> 403 / 42501 (was 201, and the row carried 1970)
--   POST  /rest/v1/scores  {"millis":1234,"hits":5,"top_speed":2}
--                                    -> 201, unchanged
--   PATCH /rest/v1/profiles?id=eq.<uid> {"display_name_changed_at":null}
--                                    -> 403 / 42501 (was 204)
--   PATCH /rest/v1/profiles?id=eq.<uid> {"provider":"google"}
--                                    -> 403 / 42501 (was 204)
--   PATCH /rest/v1/profiles?id=eq.<uid> {"display_name":"Whatever"}
--                                    -> 204, or the display_name_cooldown error, unchanged
--
-- And on a scratch database, the names that used to abort signup:
--
--   insert into auth.users (id, email, raw_user_meta_data)
--   values (gen_random_uuid(), 't1@x.test', '{"full_name":"-bartek-"}'::jsonb);
--   -- was: violates check constraint "display_name_shape"; now: profile "bartek"
--
--   ... repeat with "_dev" -> "dev", "._." -> "Player", "x." -> "Player",
--       "李雷" -> "Player", and a row with a null email.
--
--   select p.proname, p.proconfig
--   from pg_proc p join pg_namespace n on n.oid = p.pronamespace
--   where n.nspname = 'public';
--   -- every row shows search_path=public, pg_temp
