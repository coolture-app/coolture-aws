from collections.abc import Mapping, Sequence

from settings import settings


def cosines(post: Sequence[float], kinds: Mapping[str, Sequence[float]]) -> dict[str, float]:
    """Both sides are stored normalised, so the dot product is the cosine."""
    return {key: sum(a * b for a, b in zip(post, vector)) for key, vector in kinds.items()}


def derive(scores: Mapping[str, float]) -> list[str]:
    """
    A post's kinds, best first: the closest kind, plus the second when within the margin. Dla dzieci is a kind like the
    others there, but it's kept, or added last, only when it's close enough to the best other kind, since the switch in
    Filtry reads it and must be precise. The rule and constants were measured in benchmarks/event-kinds.
    """
    ranked = sorted(scores, key=scores.__getitem__, reverse=True)
    kept = ranked[:1]
    if len(ranked) > 1 and scores[ranked[0]] - scores[ranked[1]] <= settings.kinds_second_margin:
        kept.append(ranked[1])

    kids = settings.kids_kind
    if kids in scores:
        others = [score for key, score in scores.items() if key != kids]
        kept = [key for key in kept if key != kids]
        if not others or scores[kids] - max(others) >= settings.kids_min_lead:
            kept.append(kids)
    return sorted(kept, key=scores.__getitem__, reverse=True)
