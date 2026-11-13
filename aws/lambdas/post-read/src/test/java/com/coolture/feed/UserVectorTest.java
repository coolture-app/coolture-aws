package com.coolture.feed;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserVectorTest {

    private static final double[] A = {1, 0};
    private static final double[] B = {0, 1};

    private static UserVector.Stance stance(String type, double[] sum, double amount) {
        return new UserVector.Stance(type, sum, amount);
    }

    private static UserVector.Parts stances(UserVector.Stance... stances) {
        return new UserVector.Parts(List.of(stances), null, List.of(), null, null);
    }

    private static UserVector.Parts withInterests(double[] interests, UserVector.Stance... stances) {
        return new UserVector.Parts(List.of(stances), interests, List.of(), null, null);
    }

    @Test
    void blend_weighsEachStanceAndNormalises() {
        double[] vector = UserVector.blend(stances(stance("LIKE", A, 0), stance("TAKES_PART", B, 0)));

        assertArrayEquals(new double[]{1 / Math.sqrt(5), 2 / Math.sqrt(5)}, vector, 1e-12);
    }

    @Test
    void blend_ignoresAStanceItHasNoWeightFor() {
        double[] vector = UserVector.blend(stances(stance("LIKE", A, 1), stance("NOT_INTERESTED", B, 50)));

        assertArrayEquals(A, vector, 1e-12);
    }

    @Test
    void blend_isNull_withNothingToBuildFrom() {
        assertNull(UserVector.blend(stances()));
        assertNull(UserVector.blend(stances(stance("LIKE", new double[]{0, 0}, 1))));
    }

    @Test
    void blend_addsTheCategoryAverageToEachPick_atALowerWeight() {
        double[] music = {1, 1};
        UserVector.Parts parts = new UserVector.Parts(List.of(), A, List.of(new UserVector.Category(music, 1)), null, null);

        double[] expected = {1 + 0.25 / Math.sqrt(2), 0.25 / Math.sqrt(2)};
        double norm = Math.hypot(expected[0], expected[1]);
        assertArrayEquals(new double[]{expected[0] / norm, expected[1] / norm}, UserVector.blend(parts), 1e-12);
    }

    @Test
    void blend_usesInterestsAlone_withNoStances() {
        assertArrayEquals(B, UserVector.blend(withInterests(B)), 1e-12);
    }

    @Test
    void blend_usesStancesAlone_withNoInterests() {
        assertArrayEquals(A, UserVector.blend(stances(stance("LIKE", A, 50))), 1e-12);
    }

    @Test
    void blend_movesTowardsStances_asEngagementGrows() {
        double[] newUser = UserVector.blend(withInterests(B, stance("LIKE", A, 0)));
        double[] activeUser = UserVector.blend(withInterests(B, stance("LIKE", A, 100)));

        assertArrayEquals(B, newUser, 1e-12);
        assertTrue(activeUser[0] > activeUser[1] && activeUser[1] > 0);
    }

    @Test
    void blend_countsEngagementByStanceWeight() {
        double[] oneLike = UserVector.blend(withInterests(B, stance("LIKE", A, 1)));
        double[] oneGoing = UserVector.blend(withInterests(B, stance("TAKES_PART", A, 1)));

        assertTrue(oneGoing[0] > oneLike[0], "going counts twice a like towards engagement");
    }

    @Test
    void blend_givesInterestsBackTheirShare_asStancesFade() {
        double[] fresh = UserVector.blend(withInterests(B, stance("LIKE", A, 10)));
        double[] faded = UserVector.blend(withInterests(B, stance("LIKE", A, 10 * 0.05)));

        assertTrue(faded[1] > fresh[1], "faded likes count less towards engagement, so interests lead again");
    }

    // Every text of a kind shares {0, 1, 0}; what's left of each part is X for the stances and Z for the interests.
    private static final double[] X = {1, 0, 0};
    private static final double[] Z = {0, 0, 1};
    private static final double[] SHARED = {0, Math.sqrt(0.5), 0};
    private static final double[] LIKED = {1, 1, 0};
    private static final double[] PICKED = {0, 1, 1};

    @Test
    void blend_takesThePostAverageOutOfTheStances() {
        UserVector.Parts parts = new UserVector.Parts(List.of(stance("LIKE", LIKED, 1)), null, List.of(), SHARED, SHARED);

        assertArrayEquals(X, UserVector.blend(parts), 1e-12);
    }

    @Test
    void blend_takesTheInterestAverageOutOfTheInterests() {
        UserVector.Parts parts = new UserVector.Parts(List.of(), PICKED, List.of(), SHARED, SHARED);

        assertArrayEquals(Z, UserVector.blend(parts), 1e-12);
    }

    @Test
    void blend_blendsWhatIsLeftOfEachPart_eachWithItsOwnAverage() {
        double[] liked = {1, 0, 1};
        double[] postAverage = {0, 0, Math.sqrt(0.5)};
        UserVector.Parts parts = new UserVector.Parts(List.of(stance("LIKE", liked, 100)), PICKED, List.of(),
            SHARED, postAverage);

        double share = FeedConfig.INTEREST_SHARE_MIN;
        double norm = Math.hypot(share, 1 - share);
        assertArrayEquals(new double[]{(1 - share) / norm, 0, share / norm}, UserVector.blend(parts), 1e-12);
    }

    @Test
    void blend_aPartThatIsOnlyTheAverage_saysNothing() {
        double[] average = {Math.sqrt(0.5), Math.sqrt(0.5), 0};
        UserVector.Parts parts = new UserVector.Parts(List.of(stance("LIKE", LIKED, 100)), Z, List.of(), null, average);

        assertArrayEquals(Z, UserVector.blend(parts), 1e-12);
    }

    @Test
    void interestShare_neverFallsBelowTheFloor() {
        assertEquals(1.0, UserVector.interestShare(0), 1e-12);
        assertEquals(Math.exp(-1), UserVector.interestShare(FeedConfig.INTEREST_DECAY), 1e-12);
        assertEquals(0.2, UserVector.interestShare(1000), 1e-12);
    }

    @Test
    void toSql_isPgvectorsTextForm() {
        assertEquals("[1.0,-0.5]", UserVector.toSql(new double[]{1, -0.5}));
        assertNull(UserVector.toSql(null));
    }
}
