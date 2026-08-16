-- Whaaack!: a colliding display name gets the next *number*, not the next attempt.
--
-- Uniqueness itself was never in question and is not changed here. `profiles.display_name` is
-- `citext` under `profiles_display_name_key`, so "zenek" and "Zenek" are one name and the
-- database refuses the second whichever way it arrives — signup trigger, or a PATCH from the
-- rename sheet. What this migration changes is what happens when that refusal lands during
-- signup, where nobody is at the keyboard to be told.
--
-- Two things were wrong with the suffix `handle_new_user()` picked.
--
--   1. It counted attempts, not names. `suffix` started at 1 and rose by one per failed
--      insert, inside a loop of twelve. So the twelfth player called Zenek walked
--      Zenek 2 … Zenek 13, found every one taken, ran out of loop and was handed
--      'Player 4f2a91c0d3b8' — their name replaced by a uuid fragment, with nothing said to
--      them and only a `raise warning` in the log. The retry budget exists for the
--      concurrent-signup race; spending it walking a sequence that could be read straight out
--      of the table meant any popular name burned the budget on its own. A gamer tag like
--      "Kowalski", or the 'Player' fallback that every name with no ASCII in it lands on, gets
--      there on an ordinary day.
--
--   2. The separator was a space — 'Zenek 2'. The name wanted is 'Zenek2': one name rather
--      than two words, which is also what stops the numbered variant reading like a different
--      player when it appears next to the original on the board.
--
-- The retry loop itself stays, unchanged in shape and for the same reason it was written: two
-- signups resolving to the same name is a race no look-then-leap can close, and losing it must
-- cost a retry rather than cost the account. What changes is where the retries start from —
-- one query, at the first collision, instead of one insert per number.
--
-- Still true, and still the rule this function is written to: it may fail to pick a *pretty*
-- name, but it may never fail to create one. It runs AFTER INSERT on auth.users, so anything
-- it raises rolls the account back and signup answers 500. An odd display name is recoverable
-- in Settings; a blocked signup is not.

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    candidate text;
    base      text;
    stem      text;
    fallback  text;
    conflict  text;
    suffix    bigint := 1;
    -- Whether the sequence has been read yet. A plain `suffix = 1` test would have served
    -- for every input I could construct, and that is exactly the kind of reasoning this
    -- function should not rest on: the seed can legitimately come back as 1 (someone is
    -- already called Zenek1), and a flag says "once, at the first collision" without
    -- depending on which numbers happen to be in the table.
    seeded    boolean := false;
    provider  text;
begin
    provider := coalesce(new.raw_app_meta_data ->> 'provider', 'email');

    -- Google hands us the account name, Play Games the gamer tag (by way of the edge
    -- function's user_metadata), and email signups send their own display_name.
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

    -- Always available and always valid: 'Player' plus twelve hex digits is 18 characters,
    -- inside the 2..24 length rule, starting on a letter and ending on a hex digit. No space
    -- in it any more either, for the same reason the suffix lost its one.
    fallback := 'Player' || left(replace(new.id::text, '-', ''), 12);

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

                if not seeded then
                    seeded := true;
                    -- The plain name is taken. Read the sequence instead of walking it: the
                    -- highest number already handed out on this stem, so one query settles
                    -- what twelve inserts used to only approach. `1` when there are none,
                    -- which the increment below turns into the 2 that starts every sequence.
                    --
                    -- Matched by prefix length rather than by a regexp built out of `base`,
                    -- because `base` is player-supplied and display names may legally contain
                    -- '.', '-' and '_' — all of which mean something to one of regexp/LIKE,
                    -- and none of which can be escaped here without inviting the bug that
                    -- escaping bugs always are. Comparing a fixed-length prefix needs no
                    -- escaping at all. The `{1,9}` bound keeps the cast below inside bigint
                    -- whatever is in the column, and `greatest(..., 1)` keeps a name ending
                    -- in 0 from seeding a sequence that starts below where it should.
                    --
                    -- A sequential scan, and deliberately so: no index can serve a prefix
                    -- comparison of arbitrary length, and this runs only on the collision
                    -- branch of a signup — never on the common path.
                    --
                    -- Wrapped in its own handler because this is the one statement in the
                    -- function that runs *inside* an exception handler, where a raise is not
                    -- caught by the block it appears in — it would escape the loop, escape
                    -- the trigger, and take the signup with it. Falling back to a plain
                    -- increment picks a worse number; it does not cost anybody an account.
                    begin
                        select greatest(coalesce(
                                   max(substring(p.display_name::text
                                                 from char_length(base) + 1)::bigint),
                                   1), 1)
                          into suffix
                          from public.profiles p
                         where lower(left(p.display_name::text, char_length(base))) = lower(base)
                           and substring(p.display_name::text from char_length(base) + 1)
                               ~ '^[0-9]{1,9}$';
                    exception when others then
                        raise warning 'handle_new_user: could not read the sequence for % — %',
                                      base, sqlerrm;
                        suffix := 1;
                    end;
                end if;

                -- On the first collision this steps past the highest number in use; on any
                -- later one it is absorbing a genuine race, where +1 is exactly right.
                suffix := suffix + 1;

                -- Make room for the digits, then re-trim the trailing edge, because that cut
                -- can land on punctuation and `display_name_shape` demands the last character
                -- be alphanumeric. The digits themselves always satisfy that, so only the
                -- stem's own tail can break it.
                stem := regexp_replace(
                            left(base, 24 - char_length(suffix::text)),
                            '[^A-Za-z0-9]+$', '', 'g'
                        );
                candidate := stem || suffix::text;
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

    -- Twelve *races* lost on one insert, which is a different and far less likely thing than
    -- the twelve ordinary collisions this loop used to spend itself on. Take the uuid-derived
    -- name; if even that fails, leave the account without a profile rather than leaving the
    -- player without an account.
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
    'Signup trigger. Derives a valid, unique display name — the next free number on a taken '
    'name — and never raises: a blocked signup is unrecoverable, an odd display name is not.';

-- ---------------------------------------------------------------------------- verifying
--
-- On a scratch database. The numbering, which is what changed:
--
--   insert into auth.users (id, email, raw_user_meta_data)
--   select gen_random_uuid(), 'z' || i || '@x.test', '{"display_name":"Zenek"}'::jsonb
--   from generate_series(1, 15) i;
--
--   select display_name from public.profiles order by created_at;
--   -- Zenek, Zenek2, Zenek3 ... Zenek15
--   -- was:  Zenek, Zenek 2 ... Zenek 12, then 'Player <hex>' for the 12th onwards
--
-- That the sequence is *read*, not walked — delete a hole in the middle and the next signup
-- still goes past the top rather than filling it, which is what keeps one query enough:
--
--   delete from auth.users where id = (select id from public.profiles where display_name = 'Zenek7');
--   -- next Zenek signup -> Zenek16, not Zenek7
--
-- That the names needing a fallback still get one, and that none of them raise:
--
--   insert into auth.users (id, email, raw_user_meta_data) values
--     (gen_random_uuid(), 't1@x.test', '{"full_name":"-bartek-"}'::jsonb),   -- bartek
--     (gen_random_uuid(), 't2@x.test', '{"full_name":"_dev"}'::jsonb),       -- dev
--     (gen_random_uuid(), 't3@x.test', '{"full_name":"._."}'::jsonb),        -- Player
--     (gen_random_uuid(), 't4@x.test', '{"full_name":"李雷"}'::jsonb),        -- Player2
--     (gen_random_uuid(), null,        '{}'::jsonb);                         -- Player3
--
-- That a name legally containing regexp/LIKE metacharacters numbers like any other, which is
-- the case the prefix comparison exists for:
--
--   insert into auth.users (id, email, raw_user_meta_data) values
--     (gen_random_uuid(), 'm1@x.test', '{"display_name":"a.b_c-d"}'::jsonb),
--     (gen_random_uuid(), 'm2@x.test', '{"display_name":"a.b_c-d"}'::jsonb);
--   -- a.b_c-d, then a.b_c-d2
--
-- That the 24-character ceiling holds with the digits on:
--
--   insert into auth.users (id, email, raw_user_meta_data)
--   select gen_random_uuid(), 'L' || i || '@x.test',
--          '{"display_name":"abcdefghijklmnopqrstuvwx"}'::jsonb
--   from generate_series(1, 3) i;
--   -- abcdefghijklmnopqrstuvwx, abcdefghijklmnopqrstuvw2, abcdefghijklmnopqrstuvw3
--   select max(char_length(display_name)) from public.profiles;  -- 24
--
-- And that uniqueness is still the database's rule rather than this function's, case folded:
--
--   insert into public.profiles (id, display_name) values (gen_random_uuid(), 'ZENEK');
--   -- ERROR: duplicate key value violates unique constraint "profiles_display_name_key"
