-- Whaaack! Zepp OS edition: its own scores table and its own pair of boards.
--
-- Why a separate table rather than a `platform` column on public.scores
--
--   The watch game is not the phone game with a smaller screen. It plays on a 3x3 board
--   instead of 4x4, opens at most five concurrent fruit instead of sixteen, and is tapped
--   with one finger on a 35mm circle. Its whole difficulty curve is retuned around that,
--   so a millisecond survived on a watch and a millisecond survived on a phone are not the
--   same unit and must never be sorted against each other — a shared board would just be a
--   list of whichever platform happens to be more forgiving.
--
--   Splitting the *table* rather than filtering one keeps that separation structural. The
--   phone's `leaderboard()` and `my_standing()` are untouched by this migration and cannot
--   accidentally start including watch rows; the plausibility constraints below are derived
--   from the watch engine's own curve and are free to differ from the phone's without
--   either set having to carry an `if platform = ...` in it.
--
-- Everything else deliberately mirrors public.scores: same identity model (the profile is
-- shared, so a player is one player with two scores), same RLS shape, same column grants,
-- same flood trigger, same SECURITY DEFINER read path so raw rows stay unreadable.

-- ------------------------------------------------------------------------- zepp_scores

create table if not exists public.zepp_scores (
    id         bigint generated always as identity primary key,
    user_id    uuid        not null references auth.users (id) on delete cascade
                           default auth.uid(),
    millis     integer     not null check (millis >= 0),
    hits       integer     not null default 0 check (hits >= 0),
    created_at timestamptz not null default now()
);

create index if not exists zepp_scores_user_best_idx
    on public.zepp_scores (user_id, millis desc);
create index if not exists zepp_scores_recent_idx
    on public.zepp_scores (created_at desc);
create index if not exists zepp_scores_user_recent_idx
    on public.zepp_scores (user_id, created_at desc);

comment on table public.zepp_scores is
    'One row per completed ranked run of the Zepp OS edition. millis = milliseconds survived.';

-- --------------------------------------------------------------- score plausibility

-- The same reasoning as the phone's constraints, re-derived from the watch curve. None of
-- these is anti-cheat and none is claimed to be: the client authors the score, so any
-- arithmetic rule can be satisfied by a caller willing to do the arithmetic. What they buy
-- is that a forged score has to be internally coherent and has to sit inside the range the
-- watch game can actually produce, where a real player can reach it.

-- Ten minutes, matching the phone. The watch curve tops out near 5.6 fruit a second with
-- five slots on nine tiles, which no run survives for anything like this long — so it is a
-- ceiling against a forgery owning the board for ever, not a rule a good run can meet.
alter table public.zepp_scores drop constraint if exists zepp_scores_millis_plausible;
alter table public.zepp_scores add constraint zepp_scores_millis_plausible
    check (millis <= 600_000) not valid;

-- Arrival rate ceiling. The watch engine's pressure — targets x 1000 / (interval x 0.35 +
-- life) — passes 5.6/s deep in the tail and climbs only by fractions after that, so twelve
-- hits a second is comfortably above anything the board can serve. The +20 covers very
-- short runs, where integer division of millis leaves nothing to work with.
alter table public.zepp_scores drop constraint if exists zepp_scores_hits_plausible;
alter table public.zepp_scores add constraint zepp_scores_hits_plausible
    check (hits <= 12 * (millis / 1000) + 20) not valid;

-- Hits floor: surviving implies whacking. The gentlest stretch the watch engine can
-- produce is level 0 — 2 slots, START_LIFE_MS 1_400, and a refill wait of
-- START_INTERVAL_MS 900 x (SPAWN_GAP 0.35 + SPAWN_JITTER 0.45 worst case) = 720ms, so a
-- slot cycles at worst every 2_120ms and two of them arrive at 0.94 fruit per second.
-- One hit per 2.5 seconds is well under half of that, and the -3 covers the strikes a
-- player is allowed. Integer division floors, and a short run gives a negative bound that
-- `hits >= 0` already satisfies.
alter table public.zepp_scores drop constraint if exists zepp_scores_hits_floor;
alter table public.zepp_scores add constraint zepp_scores_hits_floor
    check (hits >= millis / 2500 - 3) not valid;

comment on constraint zepp_scores_millis_plausible on public.zepp_scores is
    'Ten minutes: far past anything the watch curve leaves survivable.';
comment on constraint zepp_scores_hits_floor on public.zepp_scores is
    'Surviving implies whacking: under half the engine''s slowest arrival rate, less 3 strikes.';

-- A run opens with a three-second countdown it cannot be lost during, so a player cannot
-- honestly finish twenty of them inside a minute. A flood stop, not a gameplay rule.
create or replace function public.rate_limit_zepp_scores()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    recent int;
begin
    select count(*) into recent
    from public.zepp_scores s
    where s.user_id = new.user_id
      and s.created_at > now() - interval '1 minute';

    if recent >= 20 then
        raise exception 'score_rate_limited'
            using hint = 'Too many runs submitted at once.';
    end if;

    return new;
end;
$$;

drop trigger if exists zepp_scores_rate_limit on public.zepp_scores;
create trigger zepp_scores_rate_limit
    before insert on public.zepp_scores
    for each row execute function public.rate_limit_zepp_scores();

-- ---------------------------------------------------------------------------- RLS

alter table public.zepp_scores enable row level security;

drop policy if exists "players read their own zepp scores" on public.zepp_scores;
create policy "players read their own zepp scores"
    on public.zepp_scores for select
    using (auth.uid() = user_id);

drop policy if exists "players record their own zepp scores" on public.zepp_scores;
create policy "players record their own zepp scores"
    on public.zepp_scores for insert
    with check (auth.uid() = user_id);

-- ------------------------------------------------------------- writable columns

-- Same reasoning as the phone's write grants (migration 20260814120000): a column-level
-- REVOKE cannot subtract from a table-level GRANT, so the table privilege is dropped first
-- and the columns granted back explicitly. `created_at` breaks both board orderings and
-- the weekly filter if a client can choose it, so it is reachable only through its default;
-- `id` is `generated always as identity` and refuses an explicit value anyway.
revoke insert, update, delete on public.zepp_scores from anon, authenticated;
grant insert (user_id, millis, hits) on public.zepp_scores to authenticated;

-- There is no UPDATE and no DELETE policy, so RLS already denied both. The revoke says so
-- out loud rather than leaving it to read as an oversight.

-- ------------------------------------------------------------------- leaderboards

create or replace function public.zepp_leaderboard(
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
        from public.zepp_scores s
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

comment on function public.zepp_leaderboard is
    'Ranked standings for the Zepp OS edition. p_scope is ''all_time'' or ''weekly''; '
    'one row per player, their best run. Shares public.profiles with the phone board, '
    'so a player carries one name across both.';

-- The caller's own standing across the entire board, not just the page a client can read.
create or replace function public.zepp_my_standing(p_scope text default 'all_time')
returns table (rank bigint, millis integer, total_players bigint)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with scoped as (
        select s.user_id, s.millis, s.created_at
        from public.zepp_scores s
        where p_scope <> 'weekly'
           or s.created_at >= public.current_week_start()
    ),
    best as (
        select distinct on (scoped.user_id)
               scoped.user_id, scoped.millis, scoped.created_at
        from scoped
        order by scoped.user_id, scoped.millis desc, scoped.created_at asc
    ),
    ranked as (
        select b.user_id,
               b.millis,
               row_number() over (order by b.millis desc, b.created_at asc) as rank,
               count(*) over () as total_players
        from best b
        join public.profiles p on p.id = b.user_id
    )
    select r.rank, r.millis, r.total_players
    from ranked r
    where r.user_id = auth.uid();
$$;

-- --------------------------------------------------------------------------- grants

revoke all on function public.zepp_leaderboard(text, int) from public;
revoke all on function public.zepp_my_standing(text)      from public;

-- Anonymous clients may browse the board; only authenticated ones can see their own
-- standing. Same split as the phone's, and the same reason: the board is public reading,
-- a standing is about a specific caller.
grant execute on function public.zepp_leaderboard(text, int) to anon, authenticated;
grant execute on function public.zepp_my_standing(text)      to authenticated;
