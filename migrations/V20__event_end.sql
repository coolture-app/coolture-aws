-- The SQL side of EventTime, which holds the rule. STABLE, as timestamptz + interval is: a SQL function no stricter than
-- its body is inlined, so a query plans as with the CASE written out.

CREATE FUNCTION event_end(starts_at TIMESTAMPTZ, ends_at TIMESTAMPTZ, open_ended BOOLEAN) RETURNS TIMESTAMPTZ
    LANGUAGE sql STABLE PARALLEL SAFE
    RETURN CASE WHEN open_ended THEN 'infinity'::timestamptz ELSE COALESCE(ends_at, starts_at + interval '3 hours') END;
