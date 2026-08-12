-- Whaaack! core schema: player profiles, run scores, and the two leaderboards.
--
-- Design notes
--   * Only signed-in players are ranked. Casual runs never reach this database.
--   * A player's leaderboard entry is their *best* run, not their most recent, so the
--     board is stable and re-playing can only ever improve a position.
--   * Leaderboards are exposed through SECURITY DEFINER functions rather than a table
--     grant, so a client can read aggregate standings without being able to enumerate
--     raw score rows belonging to other people.

create extension if not exists citext;

-- ---------------------------------------------------------------------------- profiles

create table if not exists public.profiles (
    id                      uuid primary key references auth.users (id) on delete cascade,
    display_name            citext      not null,
    provider                text        not null default 'email',
    created_at              timestamptz not null default now(),
    display_name_changed_at timestamptz,
    constraint display_name_length check (char_length(display_name) between 2 and 24),
    constraint display_name_shape check (display_name ~ '^[A-Za-z0-9](?:[A-Za-z0-9 ._-]*[A-Za-z0-9])?$')
);

-- citext already folds case, so this single index is the case-insensitive uniqueness rule.
create unique index if not exists profiles_display_name_key
    on public.profiles (display_name);

comment on table public.profiles is
    'Public-facing player identity. One row per auth user, created by a signup trigger.';

-- ------------------------------------------------------------------------------ scores

create table if not exists public.scores (
    id         bigint generated always as identity primary key,
    user_id    uuid        not null references auth.users (id) on delete cascade
                           default auth.uid(),
    millis     integer     not null check (millis >= 0 and millis <= 86_400_000),
    hits       integer     not null default 0 check (hits >= 0),
    top_speed  integer     not null default 0 check (top_speed >= 0),
    created_at timestamptz not null default now()
);

create index if not exists scores_user_best_idx
    on public.scores (user_id, millis desc);
create index if not exists scores_recent_idx
    on public.scores (created_at desc);

comment on table public.scores is
    'One row per completed ranked run. millis = milliseconds survived, the score.';

-- ------------------------------------------------------------------- signup provisioning

-- Turns a raw signup into a profile, picking the best available display name and
-- de-duplicating it against the leaderboard.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    candidate  text;
    base       text;
    suffix     int := 1;
    provider   text;
begin
    provider := coalesce(new.raw_app_meta_data ->> 'provider', 'email');

    -- Google hands us the account name; email signups send their own display_name.
    candidate := coalesce(
        nullif(trim(new.raw_user_meta_data ->> 'display_name'), ''),
        nullif(trim(new.raw_user_meta_data ->> 'full_name'), ''),
        nullif(trim(new.raw_user_meta_data ->> 'name'), ''),
        split_part(new.email, '@', 1)
    );

    -- Squeeze it into the shape the check constraint accepts.
    candidate := regexp_replace(candidate, '[^A-Za-z0-9 ._-]', '', 'g');
    candidate := trim(candidate);
    if char_length(candidate) < 2 then
        candidate := 'Player';
    end if;
    candidate := left(candidate, 24);
    base := candidate;

    while exists (select 1 from public.profiles p where p.display_name = candidate) loop
        suffix := suffix + 1;
        candidate := left(base, 24 - char_length(suffix::text) - 1) || ' ' || suffix::text;
    end loop;

    insert into public.profiles (id, display_name, provider)
    values (new.id, candidate, provider);

    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

-- Display names may only change once every 30 days, matching the settings copy.
create or replace function public.enforce_display_name_cooldown()
returns trigger
language plpgsql
as $$
begin
    if new.display_name is distinct from old.display_name then
        if old.display_name_changed_at is not null
           and old.display_name_changed_at > now() - interval '30 days' then
            raise exception 'display_name_cooldown'
                using hint = 'You can change your display name once every 30 days.';
        end if;
        new.display_name_changed_at := now();
    end if;
    return new;
end;
$$;

drop trigger if exists profiles_display_name_cooldown on public.profiles;
create trigger profiles_display_name_cooldown
    before update on public.profiles
    for each row execute function public.enforce_display_name_cooldown();

-- ------------------------------------------------------------------------------- RLS

alter table public.profiles enable row level security;
alter table public.scores   enable row level security;

drop policy if exists "profiles are readable by everyone" on public.profiles;
create policy "profiles are readable by everyone"
    on public.profiles for select
    using (true);

drop policy if exists "players update their own profile" on public.profiles;
create policy "players update their own profile"
    on public.profiles for update
    using (auth.uid() = id)
    with check (auth.uid() = id);

drop policy if exists "players read their own scores" on public.scores;
create policy "players read their own scores"
    on public.scores for select
    using (auth.uid() = user_id);

drop policy if exists "players record their own scores" on public.scores;
create policy "players record their own scores"
    on public.scores for insert
    with check (auth.uid() = user_id);

-- ----------------------------------------------------------------------- leaderboards

-- Start of the current ISO week (Monday 00:00 UTC).
create or replace function public.current_week_start()
returns timestamptz
language sql
stable
as $$
    select date_trunc('week', (now() at time zone 'utc')) at time zone 'utc';
$$;

create or replace function public.leaderboard(
    p_scope text default 'all_time',
    p_limit int  default 50
)
returns table (
    rank         bigint,
    user_id      uuid,
    display_name text,
    millis       integer,
    achieved_at  timestamptz
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with scoped as (
        select s.user_id, s.millis, s.created_at
        from public.scores s
        where p_scope <> 'weekly'
           or s.created_at >= public.current_week_start()
    ),
    best as (
        select distinct on (scoped.user_id)
               scoped.user_id, scoped.millis, scoped.created_at
        from scoped
        order by scoped.user_id, scoped.millis desc, scoped.created_at asc
    )
    select row_number() over (order by b.millis desc, b.created_at asc) as rank,
           b.user_id,
           p.display_name::text,
           b.millis,
           b.created_at
    from best b
    join public.profiles p on p.id = b.user_id
    order by b.millis desc, b.created_at asc
    limit greatest(1, least(p_limit, 200));
$$;

comment on function public.leaderboard is
    'Ranked standings. p_scope is ''all_time'' or ''weekly''; one row per player, their best run.';

-- The caller's own standing, which may sit far below the returned page of the board.
create or replace function public.my_standing(p_scope text default 'all_time')
returns table (rank bigint, millis integer, total_players bigint)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with board as (
        select * from public.leaderboard(p_scope, 200)
    )
    select b.rank, b.millis, (select count(*) from board)
    from board b
    where b.user_id = auth.uid();
$$;

-- ------------------------------------------------------------------ account deletion

-- Lets a signed-in player erase themselves without handing the client a service key.
create or replace function public.delete_my_account()
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    uid uuid := auth.uid();
begin
    if uid is null then
        raise exception 'not_authenticated';
    end if;
    -- profiles and scores cascade from auth.users.
    delete from auth.users where id = uid;
end;
$$;

-- --------------------------------------------------------------------------- grants

revoke all on function public.leaderboard(text, int)   from public;
revoke all on function public.my_standing(text)        from public;
revoke all on function public.delete_my_account()      from public;

-- Anonymous players may browse the boards; only authenticated ones can see their own
-- standing or delete their account.
grant execute on function public.leaderboard(text, int) to anon, authenticated;
grant execute on function public.my_standing(text)      to authenticated;
grant execute on function public.delete_my_account()    to authenticated;
