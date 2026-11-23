from collections.abc import Mapping, Sequence

# Measured on labelled posts for the current model, so measure them again after a model change (see the README).
SECOND_MARGIN = 0.0085
KIDS_KIND = "kids"
KIDS_MIN_LEAD = -0.0019


def cosines(post: Sequence[float], kinds: Mapping[str, Sequence[float]]) -> dict[str, float]:
    """Both sides are stored normalised, so the dot product is the cosine."""
    return {key: sum(a * b for a, b in zip(post, vector)) for key, vector in kinds.items()}


def derive(scores: Mapping[str, float]) -> list[str]:
    """
    A post's kinds, best first: the closest kind, plus the second when within the margin. Dla dzieci is left out of that
    ranking, so it never takes the second slot. It's added only when it's close enough to the best other kind, since
    the switch in Filtry reads it and must be precise. Left alone, it gets the best other kind beside it, so a
    children's concert is found by Koncerty with Dla dzieci on.
    """
    kids = KIDS_KIND
    others = sorted((key for key in scores if key != kids), key=scores.__getitem__, reverse=True)
    kept = others[:1]
    if len(others) > 1 and scores[others[0]] - scores[others[1]] <= SECOND_MARGIN:
        kept.append(others[1])
    if kids in scores and (not others or scores[kids] - scores[others[0]] >= KIDS_MIN_LEAD):
        kept.append(kids)
    return sorted(kept, key=scores.__getitem__, reverse=True)
