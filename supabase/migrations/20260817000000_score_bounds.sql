-- Whaaack! score bounds: a ceiling a real run can reach, and a floor under the hit count.
--
-- The hardening pass bounded `hits` and `top_speed` against the run's length but left the
-- run's length itself on the ceiling init.sql gave it — 86_400_000, a full day. Nothing else
-- constrains it, and the boards order by `millis desc`, so one authenticated request
--
--     POST /rest/v1/scores  {"millis": 86400000, "hits": 0, "top_speed": 0}
--
-- passed every check (`hits <= 40 * (millis / 1000) + 20` is satisfied by zero, `top_speed <= 64`
-- by zero, the flood trigger by a first row) and took rank 1 all-time and weekly with a score
-- no legitimate run could ever equal, let alone beat. That is the whole leaderboard, ended by
-- a single call, permanently.
--
-- Both constraints below are deliberately loose, in the spirit of the ones beside them: they
-- exist to stop a forged score from *owning the board*, not to referee a good one. Neither is
-- anti-cheat and neither is claimed to be — the client authors the score, so any purely
-- arithmetic rule can be satisfied by a caller willing to do the arithmetic. What they buy is
-- that a forgery has to be internally coherent and has to sit inside the range the game can
-- actually produce, where a real player can reach it.
--
-- NOT VALID on both, like the plausibility pair before them: they bind every future insert,
-- which is the point, without risking the migration against whatever is already in the table.

-- ------------------------------------------------------------------------ millis ceiling

-- Derived from the engine's own curve rather than picked (see GameEngine's companion):
-- LEVEL_STEP_MS is 4_000, and targetsAtLevel opens one more slot every TARGET_STEP_LEVELS = 4
-- past the fifth at level 16, so the board reaches MAX_TARGETS = 16 fruit on 16 tiles at
-- level 60 — four minutes — at which point, in the engine's own words, it "is not survivable
-- by anybody". The tuning notes put the point where pressure "passes any human" near two
-- minutes. Ten minutes is two and a half times past the level the game becomes physically
-- unplayable at and five times past the documented human ceiling, so no honest run can come
-- near it, while a forged one is now 144x smaller than the day-long score it used to be able
-- to claim.
alter table public.scores drop constraint if exists scores_millis_plausible;
alter table public.scores add constraint scores_millis_plausible
    check (millis <= 600_000) not valid;

-- --------------------------------------------------------------------------- hits floor

-- Fruit arrives on a schedule the player does not control, and a fruit that is not whacked
-- before its life expires costs a strike — three of which end the run. So all but three of
-- the fruit that ever surfaced during a run were hit, and surviving time therefore *implies*
-- a hit count. `{"millis": 600000, "hits": 0}` is not a bad run; it is not a run.
--
-- The floor is half the slowest rate the engine can produce, and the slowest is the opening
-- level: 2 slots, START_LIFE_MS = 1_250, and a refill wait of START_INTERVAL_MS = 850 x
-- (SPAWN_GAP 0.35 + SPAWN_JITTER 0.45 worst case) = 680ms, so a slot cycles at worst every
-- 1_930ms and two of them arrive at 1.04 fruit per second. Every later level is faster on
-- both tracks and has more slots. Asking for one hit per two seconds is therefore under half
-- of what the gentlest possible stretch of play forces, and the -3 covers the strikes a
-- player is allowed. Integer division floors, and a short run gives a negative bound that
-- `hits >= 0` already satisfies, so no real score can trip this.
alter table public.scores drop constraint if exists scores_hits_floor;
alter table public.scores add constraint scores_hits_floor
    check (hits >= millis / 2000 - 3) not valid;

comment on constraint scores_millis_plausible on public.scores is
    'Ten minutes: past the level the board saturates at, so no honest run reaches it.';
comment on constraint scores_hits_floor on public.scores is
    'Surviving implies whacking: half the engine''s slowest arrival rate, less the 3 strikes.';
