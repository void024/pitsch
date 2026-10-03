"""Deterministic meeting-slot optimiser. No LLM.

Hard constraints (a slot is impossible if it breaks one):
  working hours & days · min notice · inside the search window · no overlap with busy time
  (including the buffer on both sides) · max meetings per day · inside the founder's stated
  availability (if any) · reasonable founder local time (08:00–20:00, if their time zone is known)

Soft preferences (scored, then explained):
  preferred windows · avoiding lunch · not back-to-back · lighter days · earliness (by priority)
  · comfortable founder local time

Selection prefers spreading suggestions across different days so the investor gets real choice.
"""

from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

from app.agents.calendar.schemas import WEEKDAY_INDEX, CalendarInput, Interval, Priority, TimeWindow

FOUNDER_HARD_HOURS = (time(8, 0), time(20, 0))
FOUNDER_COMFORT_HOURS = (time(10, 0), time(17, 0))
BACK_TO_BACK_GAP = timedelta(minutes=30)


@dataclass
class Candidate:
    start: datetime
    end: datetime
    score: float = 50.0
    reasons: list[str] = field(default_factory=list)


def _fmt(t: time | datetime) -> str:
    return t.strftime("%H:%M")


def _local(d: date, t: time, tz: ZoneInfo) -> datetime:
    return datetime.combine(d, t, tzinfo=tz)


def _overlaps(a_start, a_end, b_start, b_end) -> bool:
    return a_start < b_end and b_start < a_end


def _in_window(start: datetime, end: datetime, w: TimeWindow) -> bool:
    return w.start <= start.timetz().replace(tzinfo=None) and end.timetz().replace(tzinfo=None) <= w.end


def find_slots(p: CalendarInput, tz: ZoneInfo, start: datetime, end: datetime,
               founder_windows: list[Interval]) -> tuple[list[Candidate], int]:
    dur = timedelta(minutes=p.duration_minutes)
    buf = timedelta(minutes=p.buffer_minutes)
    step = timedelta(minutes=p.granularity_minutes)
    busy = sorted(((b.start.astimezone(tz), b.end.astimezone(tz)) for b in p.busy), key=lambda x: x[0])
    founder_tz = ZoneInfo(p.founder_timezone) if p.founder_timezone else None
    workdays = {WEEKDAY_INDEX[d] for d in p.working_hours.days}

    per_day: dict[date, int] = {}
    for b_start, _ in busy:
        per_day[b_start.date()] = per_day.get(b_start.date(), 0) + 1

    first_day, last_day = start.astimezone(tz).date(), end.astimezone(tz).date()
    total_days = max(1, (last_day - first_day).days + 1)
    candidates: list[Candidate] = []
    considered = 0

    day = first_day
    while day <= last_day:
        if day.weekday() not in workdays:
            day += timedelta(days=1)
            continue
        if p.max_meetings_per_day and per_day.get(day, 0) >= p.max_meetings_per_day:
            day += timedelta(days=1)
            continue

        t = _local(day, p.working_hours.start, tz)
        day_end = _local(day, p.working_hours.end, tz)
        while t + dur <= day_end:
            s, e = t, t + dur
            t += step
            considered += 1
            if s < start or e > end:
                continue
            if any(_overlaps(s - buf, e + buf, bs, be) for bs, be in busy):
                continue
            if founder_windows and not any(fw.start <= s and e <= fw.end for fw in founder_windows):
                continue
            if founder_tz:
                fs, fe = s.astimezone(founder_tz), e.astimezone(founder_tz)
                if fs.date() != fe.date() or not (FOUNDER_HARD_HOURS[0] <= fs.time() and fe.time() <= FOUNDER_HARD_HOURS[1]):
                    continue
            candidates.append(_score(Candidate(s, e), p, busy, per_day, day, first_day, total_days,
                                     founder_tz, bool(founder_windows)))
        day += timedelta(days=1)

    return candidates, considered


def _score(c: Candidate, p: CalendarInput, busy, per_day, day: date, first_day: date, total_days: int,
           founder_tz: ZoneInfo | None, has_founder_windows: bool) -> Candidate:
    c.reasons.append(f"Free, within working hours ({_fmt(p.working_hours.start)}–{_fmt(p.working_hours.end)})")

    before = [be for bs, be in busy if be <= c.start and be.date() == day]
    after = [bs for bs, be in busy if bs >= c.end and bs.date() == day]
    if p.buffer_minutes:
        c.reasons.append(f"At least {p.buffer_minutes} min buffer around other meetings")
    if p.avoid_back_to_back and ((before and c.start - max(before) < BACK_TO_BACK_GAP) or
                                 (after and min(after) - c.end < BACK_TO_BACK_GAP)):
        c.score -= 10
        c.reasons.append("Close to another meeting (less than 30 min gap)")

    if p.preferred_windows:
        hit = next((w for w in p.preferred_windows if _in_window(c.start, c.end, w)), None)
        if hit:
            c.score += 20
            c.reasons.append(f"In your preferred window ({_fmt(hit.start)}–{_fmt(hit.end)})")

    if p.lunch:
        ls, le = _local(day, p.lunch.start, c.start.tzinfo), _local(day, p.lunch.end, c.start.tzinfo)
        if _overlaps(c.start, c.end, ls, le):
            c.score -= 25
            c.reasons.append(f"Overlaps lunch ({_fmt(p.lunch.start)}–{_fmt(p.lunch.end)})")

    load = per_day.get(day, 0)
    if load:
        c.score -= 4 * load
        c.reasons.append(f"{load} other meeting{'s' if load > 1 else ''} that day")
    else:
        c.reasons.append("No other meetings that day")

    weight = {Priority.HIGH: 20, Priority.NORMAL: 8, Priority.LOW: 0}[p.priority]
    earliness = 1 - (day - first_day).days / total_days
    c.score += weight * earliness
    if p.priority == Priority.HIGH and day == first_day:
        c.reasons.append("Earliest available day (high-priority deal)")

    if founder_tz:
        fs = c.start.astimezone(founder_tz)
        if FOUNDER_COMFORT_HOURS[0] <= fs.time() <= FOUNDER_COMFORT_HOURS[1]:
            c.score += 5
        c.reasons.append(f"Founder local time: {fs.strftime('%a %H:%M')} ({founder_tz.key})")
    if has_founder_windows:
        c.score += 10
        c.reasons.append("Within the founder's stated availability")

    c.score = round(max(0.0, min(100.0, c.score)), 1)
    return c


def pick(candidates: list[Candidate], n: int) -> list[Candidate]:
    """Best slots, spread across days first, never overlapping each other."""
    ranked = sorted(candidates, key=lambda c: (-c.score, c.start))
    chosen: list[Candidate] = []
    days: set[date] = set()
    for spread in (True, False):
        for c in ranked:
            if len(chosen) >= n:
                break
            if c in chosen or any(_overlaps(c.start, c.end, x.start, x.end) for x in chosen):
                continue
            if spread and c.start.date() in days:
                continue
            chosen.append(c)
            days.add(c.start.date())
    return sorted(chosen, key=lambda c: (-c.score, c.start))
