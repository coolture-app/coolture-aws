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
    A post's kinds, best first: the closest kind, plus the second when within the margin. Dla dzieci is a kind like the
    others there, but it's kept, or added last, only when it's close enough to the best other kind, since the switch in
    Filtry reads it and must be precise. Left alone, it gets the best other kind beside it, so a children's concert is
    found by Koncerty with Dla dzieci on.
    """
    ranked = sorted(scores, key=scores.__getitem__, reverse=True)
    kept = ranked[:1]
    if len(ranked) > 1 and scores[ranked[0]] - scores[ranked[1]] <= SECOND_MARGIN:
        kept.append(ranked[1])

    kids = KIDS_KIND
    if kids in scores:
        others = [key for key in ranked if key != kids]
        kept = [key for key in kept if key != kids]
        if not others or scores[kids] - scores[others[0]] >= KIDS_MIN_LEAD:
            kept.append(kids)
        if kept == [kids] and others:
            kept.append(others[0])
    return sorted(kept, key=scores.__getitem__, reverse=True)
