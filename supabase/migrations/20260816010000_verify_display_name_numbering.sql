-- Whaaack!: proves handle_new_user() actually runs, and leaves nothing behind.
--
-- `create or replace function` on a plpgsql body checks its *syntax* and stops there. The SQL
-- statements inside are not resolved against the schema until the function first executes — so
-- a mistyped column in the sequence-seeding query added by the previous migration would have
-- been accepted by the push, sat there looking applied, and first appeared as a 500 on a real
-- player's signup. That is the worst possible place to discover it: `handle_new_user` runs
-- AFTER INSERT on auth.users, so anything it raises rolls the account back, and the whole
-- function is written around never doing that.
--
-- So this runs it. Fifteen signups all wanting the same name, one whose name is made of the
-- punctuation that would break a regexp built by string-concatenation, one at the length limit
-- and one with no ASCII in it at all — then it checks what came out and throws the rows away.
--
-- ## Why this is safe to run against a live database
--
-- The work happens inside a nested BEGIN … EXCEPTION, which is a savepoint. Raising inside it
-- rolls every insert back, including the auth.users rows and the profiles the trigger made
-- from them. What survives is the plpgsql *variables*, because an exception handler rolls back
-- database changes and not assignments — which is the whole trick: the findings outlive the
-- rows they were taken from, and the assertions run afterwards on data that no longer exists.
--
-- A failure raises for real and the migration is rolled back with it, so a broken trigger
-- cannot be recorded as verified. A pass commits nothing but the history row.

do $$
declare
    marker    constant text := 'whaaack-verification-rollback';
    zenek     int;
    zenek_max bigint;
    spaced    int;
    meta      text;
    longest   int;
    nonascii  text;
    failures  text := '';
begin
    begin
        -- ---- fifteen players, one name ------------------------------------------------
        insert into auth.users (id, email, raw_user_meta_data)
        select gen_random_uuid(),
               'verify-' || gen_random_uuid() || '@whaaack-verify.invalid',
               '{"display_name":"Zenek"}'::jsonb
        from generate_series(1, 15);

        -- Exactly the set Zenek, Zenek2 … Zenek15 and nothing else. Counting membership of
        -- the expected set rather than eyeballing a string catches both a wrong number and a
        -- duplicate that the unique index would have refused anyway.
        select count(*) into zenek
        from public.profiles p
        where p.display_name::text = 'Zenek'
           or p.display_name::text in (select 'Zenek' || i from generate_series(2, 15) i);

        select max(substring(p.display_name::text from 6)::bigint) into zenek_max
        from public.profiles p
        where left(p.display_name::text, 5) = 'Zenek'
          and substring(p.display_name::text from 6) ~ '^[0-9]{1,9}$';

        -- The old separator, which is what this is all replacing.
        select count(*) into spaced
        from public.profiles p
        where p.display_name::text like 'Zenek %';

        -- ---- a name made of regexp and LIKE metacharacters ------------------------------
        -- '.', '_' and '-' are all legal in a display name and all mean something to one of
        -- regexp or LIKE. This is the case the prefix comparison exists for.
        insert into auth.users (id, email, raw_user_meta_data)
        select gen_random_uuid(),
               'verify-' || gen_random_uuid() || '@whaaack-verify.invalid',
               '{"display_name":"a.b_c-d"}'::jsonb
        from generate_series(1, 2);

        select string_agg(p.display_name::text, ',' order by p.display_name::text) into meta
        from public.profiles p
        where left(p.display_name::text, 7) = 'a.b_c-d';

        -- ---- the 24-character ceiling, with the digits on -------------------------------
        insert into auth.users (id, email, raw_user_meta_data)
        select gen_random_uuid(),
               'verify-' || gen_random_uuid() || '@whaaack-verify.invalid',
               '{"display_name":"abcdefghijklmnopqrstuvwx"}'::jsonb
        from generate_series(1, 3);

        select max(char_length(p.display_name::text)) into longest from public.profiles p;

        -- ---- nothing the sanitiser can keep ---------------------------------------------
        insert into auth.users (id, email, raw_user_meta_data)
        select gen_random_uuid(),
               'verify-' || gen_random_uuid() || '@whaaack-verify.invalid',
               '{"display_name":"李雷"}'::jsonb
        from generate_series(1, 2);

        select string_agg(p.display_name::text, ',' order by p.display_name::text) into nonascii
        from public.profiles p
        where p.display_name::text in ('Player', 'Player2', 'Player 2');

        -- Everything above is now known. Throw the rows away.
        raise exception '%', marker;

    exception when raise_exception then
        -- Only ours. Anything else is a real failure of the trigger — the very thing this
        -- migration exists to catch — and must not be swallowed on its way out.
        if sqlerrm <> marker then
            raise;
        end if;
    end;

    -- ---- the rows are gone; the findings are not -------------------------------------
    if zenek <> 15 then
        failures := failures || format(
            E'\n  - fifteen signups named Zenek produced %s of the expected '
            'Zenek, Zenek2 … Zenek15', zenek);
    end if;
    if zenek_max is distinct from 15 then
        failures := failures || format(
            E'\n  - the sequence reached %s, not 15: the seeding query is not '
            'finding the numbers already in use', zenek_max);
    end if;
    if spaced <> 0 then
        failures := failures || format(
            E'\n  - %s names still use the old "Zenek 2" spacing', spaced);
    end if;
    if meta is distinct from 'a.b_c-d,a.b_c-d2' then
        failures := failures || format(
            E'\n  - a name of metacharacters numbered as %L, wanted '
            '''a.b_c-d,a.b_c-d2''', meta);
    end if;
    if longest > 24 then
        failures := failures || format(
            E'\n  - a numbered name reached %s characters, over the 24 the '
            'length constraint allows', longest);
    end if;
    if nonascii is distinct from 'Player,Player2' then
        failures := failures || format(
            E'\n  - names with no ASCII in them fell back to %L, wanted '
            '''Player,Player2''', nonascii);
    end if;

    if failures <> '' then
        raise exception 'display name numbering is wrong:%', failures;
    end if;

    raise notice 'display name numbering verified: Zenek … Zenek15, metacharacters, the 24-character ceiling and the no-ASCII fallback all correct; % test rows rolled back', 22;
end $$;
