-- Whaaack!: the previous migration's test rows are gone.
--
-- 20260816010000 signed twenty-two fake players up against the live database to prove
-- handle_new_user() actually executes, and threw them away by raising inside a nested
-- BEGIN … EXCEPTION. That is a savepoint and the rollback is Postgres', not mine — but "the
-- rows are gone" is the kind of claim that should be checked rather than reasoned about,
-- because the cost of it being wrong is twenty-two invented accounts on a public leaderboard.
--
-- Read-only. It raises if it finds anything, which rolls this migration back and leaves the
-- residue visible in the history rather than papered over.

do $$
declare
    stray_users    int;
    stray_profiles int;
begin
    select count(*) into stray_users
    from auth.users
    where email like '%@whaaack-verify.invalid';

    -- The trigger writes profiles keyed to auth.users.id, so an orphan can only exist if the
    -- cascade or the savepoint let go of one half. Checked by name for that reason: looking
    -- for profiles whose user is missing would find nothing either way.
    select count(*) into stray_profiles
    from public.profiles p
    where p.display_name::text = 'Zenek'
       or p.display_name::text ~ '^Zenek[0-9]+$'
       or p.display_name::text ~ '^a\.b_c-d[0-9]*$'
       or p.display_name::text ~ '^abcdefghijklmnopqrstuvw[x0-9][0-9]*$';

    if stray_users <> 0 or stray_profiles <> 0 then
        raise exception
            'verification residue: % auth.users at @whaaack-verify.invalid, % profiles '
            'matching the test names — delete them before this database sees real players',
            stray_users, stray_profiles;
    end if;

    raise notice 'no verification residue: the test signups rolled back cleanly';
end $$;
