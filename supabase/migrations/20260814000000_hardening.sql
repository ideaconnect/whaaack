-- Whaaack! hardening pass: an honest standing, a floor under score plausibility, and
-- profiles that are no longer enumerable by anyone who can reach PostgREST.

-- ------------------------------------------------------------------ my_standing

-- my_standing used to read its answer out of leaderboard(p_scope, 200), and leaderboard
-- clamps its own limit to 200 rows. Every player outside the top 200 therefore got no row
-- at all: the leaderboard footer showed "—" and the home card silently fell back to the
-- device's *casual* best and labelled it as their rank-worthy score. Rank over the whole
-- set instead — it is the same scan either way, just without the truncation.
create or replace function public.my_standing(p_scope text default 'all_time')
returns table (rank bigint, millis integer, total_players bigint)
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

comment on function public.my_standing is
    'The caller''s own position across the entire board, not just the page a client can read.';

revoke all on function public.my_standing(text) from public;
grant execute on function public.my_standing(text) to authenticated;

-- --------------------------------------------------------------- score plausibility

-- Scores are authored by the client, so the server has to be the one to say what a run can
-- possibly look like. These are deliberately loose: they are here to stop a forged score
-- from owning the board, not to referee a good one.

-- Four slots cycle at roughly a 200 ms interval once the curve bottoms out, so a flawless
-- player tops out near 35 fruit a second and only in the late game; 40 is above the
-- theoretical ceiling and the budget accrues across the slow early levels too. The +20
-- covers very short runs, where integer division of millis leaves nothing to work with.
--
-- NOT VALID on both: they bind every future insert, which is the entire point, without
-- risking a migration failure against whatever is already in the table.
alter table public.scores drop constraint if exists scores_hits_plausible;
alter table public.scores add constraint scores_hits_plausible
    check (hits <= 40 * (millis / 1000) + 20) not valid;

-- The HUD's speed readout saturates; nothing can report a gear the curve does not have.
alter table public.scores drop constraint if exists scores_top_speed_plausible;
alter table public.scores add constraint scores_top_speed_plausible
    check (top_speed <= 64) not valid;

create index if not exists scores_user_recent_idx
    on public.scores (user_id, created_at desc);

-- A run opens with a three-second countdown it cannot even be lost during, so a player
-- cannot honestly finish twenty of them inside a minute. This is a flood stop, not a
-- gameplay rule: legitimate play never comes near it.
create or replace function public.rate_limit_scores()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    recent int;
begin
    select count(*) into recent
    from public.scores s
    where s.user_id = new.user_id
      and s.created_at > now() - interval '1 minute';

    if recent >= 20 then
        raise exception 'score_rate_limited'
            using hint = 'Too many runs submitted at once.';
    end if;

    return new;
end;
$$;

drop trigger if exists scores_rate_limit on public.scores;
create trigger scores_rate_limit
    before insert on public.scores
    for each row execute function public.rate_limit_scores();

-- ------------------------------------------------------------------ profile privacy

-- The leaderboard is served through a SECURITY DEFINER function specifically so that raw
-- rows stay unreadable — but profiles was granted `using (true)`, which handed the same
-- names straight back through PostgREST to anyone holding the anon key. The board still
-- reads every name it needs (the function runs as its owner and bypasses RLS); a client
-- reading the table directly now only ever sees itself.
drop policy if exists "profiles are readable by everyone" on public.profiles;
drop policy if exists "players read their own profile" on public.profiles;
create policy "players read their own profile"
    on public.profiles for select
    using (auth.uid() = id);
