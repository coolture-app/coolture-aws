package com.coolture.feed;

import java.util.List;

/**
 * The viewer's taste as one vector: the posts they liked, are interested in or are going to, newer ones weighing more,
 * blended with their picked interests. Interests lead at first and give way as engagement grows, down to a floor, so a
 * few likes don't erase them, and take back over as the stances age.
 *
 * <p>Every event text shares a large part with every other, and every interest text with every other interest. Compared
 * as they are, the cosine mostly measures that shared part, and generic posts come out close to everyone. So each side
 * loses its own kind's average before the blend: the stances the average post, the interests the average interest. The
 * feed takes the average post out of each post too, and compares what's left.
 */
public final class UserVector {

    /**
     * The vectors of the posts the viewer gave one stance, each weighed by how much it has faded with age, summed, and
     * the sum of those weights: a stance from today counts 1, one a half-life old 0.5.
     */
    public record Stance(String type, double[] sum, double amount) {}

    /** The sum of every interest vector in a category the viewer picked from, and how many of their picks are in it. */
    public record Category(double[] sum, int picks) {}

    /**
     * What the database adds up for one viewer. {@code interests} is null when they picked none with a vector. The
     * averages are over every interest and every post of the model, null when there are none.
     */
    public record Parts(List<Stance> stances, double[] interests, List<Category> categories, double[] interestAverage,
                        double[] postAverage) {}

    private static final double ZERO = 1e-9;

    private UserVector() {}

    /** Null when there is nothing to build from, which leaves affinity neutral. */
    public static double[] blend(Parts parts) {
        double[] behaviour = null;
        double engagement = 0;
        for (Stance stance : parts.stances()) {
            double weight = FeedConfig.STANCE_WEIGHTS.getOrDefault(stance.type(), 0.0);
            behaviour = addScaled(behaviour, stance.sum(), weight);
            engagement += weight * stance.amount();
        }
        behaviour = centred(normalized(behaviour), parts.postAverage());

        double[] interests = scaled(parts.interests(), 1);
        for (Category category : parts.categories()) {
            double[] average = normalized(category.sum());
            if (average != null) {
                interests = addScaled(interests, average, FeedConfig.INTEREST_CATEGORY_WEIGHT * category.picks());
            }
        }
        interests = centred(normalized(interests), parts.interestAverage());

        if (interests == null) {
            return behaviour;
        }
        if (behaviour == null) {
            return interests;
        }
        double share = interestShare(engagement);
        return normalized(addScaled(scaled(interests, share), behaviour, 1 - share));
    }

    static double interestShare(double engagement) {
        return Math.max(FeedConfig.INTEREST_SHARE_MIN, Math.exp(-engagement / FeedConfig.INTEREST_DECAY));
    }

    /** pgvector's text form, for a {@code ?::vector} parameter. */
    public static String toSql(double[] vector) {
        if (vector == null) {
            return null;
        }
        StringBuilder text = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) text.append(',');
            text.append((float) vector[i]);
        }
        return text.append(']').toString();
    }

    private static double[] addScaled(double[] total, double[] vector, double weight) {
        if (vector == null) {
            return total;
        }
        double[] result = total == null ? new double[vector.length] : total;
        for (int i = 0; i < vector.length; i++) {
            result[i] += weight * vector[i];
        }
        return result;
    }

    private static double[] centred(double[] vector, double[] average) {
        if (vector == null || average == null) {
            return vector;
        }
        return normalized(addScaled(vector.clone(), average, -1));
    }

    private static double[] scaled(double[] vector, double weight) {
        return addScaled(null, vector, weight);
    }

    private static double[] normalized(double[] vector) {
        if (vector == null) {
            return null;
        }
        double norm = 0;
        for (double v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm <= ZERO) {
            return null;
        }
        double[] result = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = vector[i] / norm;
        }
        return result;
    }
}
