package me.obvgreen.ppp.math;

/**
 * Pure TrueSkill 2 (Bayesian skill rating) mathematics.
 *
 * <p>This class contains NO Bukkit/plugin logic on purpose, so the rating
 * algorithm can be tested, reviewed and tuned in isolation. It is a stateless
 * utility: every method is pure (the same inputs always produce the same
 * output). The plugin's data layer ({@code PointsManager}) decides when to
 * call these formulas and applies the results to its database.</p>
 *
 * <h2>How TrueSkill works (the idea in plain words)</h2>
 * <p>Every player is modelled as a Gaussian belief <em>N(mu, sigma)</em>:</p>
 * <ul>
 *   <li><b>mu</b> - the mean. Our best estimate of the player's skill.</li>
 *   <li><b>sigma</b> - the standard deviation. How unsure we are about that
 *       estimate. New players start with a large sigma, veterans a small one.</li>
 * </ul>
 * <p>The "conservative rating" <em>mu - 3 * sigma</em> is what a server can
 * safely advertise as a rank: a player is with (almost) certainty at least
 * that good, so it cannot be farmed by playing a single lucky game.</p>
 *
 * <p>After each match we update the beliefs with Bayesian inference:</p>
 * <ul>
 *   <li>The <b>V (mean)</b> and <b>W (variance)</b> functions describe how
 *       much a single match result changes our belief. A big upsets (favorite
 *       losing) moves the rating far more than a predictable result.</li>
 *   <li>Winners have mu raised, losers lowered; everyone's sigma shrinks a
 *       little because the game gave us information (less uncertainty).</li>
 * </ul>
 *
 * <p>Reference: Herbrich, Minka &amp; Graepel, "TrueSkill: A Bayesian Skill
 * Rating System" (NeurIPS 2006), and the TrueSkill 2 paper by Microsoft.</p>
 */
public final class TrueSkillMath {

    private TrueSkillMath() {
        // Utility class: no instances allowed.
    }

    /* =====================================================================
     * Configuration constants
     * ===================================================================*/

    /**
     * Starting mu for a brand new player. 25.0 is the value used in the
     * TrueSkill paper.
     */
    public static final double DEFAULT_MU = 25.0;

    /**
     * Starting sigma. Set so that the prior is quite uncertain
     * (mu / 3), matching the classic "default rating = 25, default deviation
     * ~ 8.33" set used by Glicko-style systems.
     */
    public static final double DEFAULT_SIGMA = DEFAULT_MU / 3.0;

    /**
     * Beta, the "game distance". It represents how predictable the outcome
     * of a match is for equally skilled players. Small beta = fewer surprises,
     * ratings move more per game. The paper uses half the initial sigma.
     */
    public static final double BETA = DEFAULT_SIGMA / 2.0;

    /**
     * Tau, the "dynamic variance" term. Every game we add a tiny bit of
     * variance to sigma BEFORE applying the match update. This models the fact
     * that a player's true skill drifts over time, and it stops sigma from
     * collapsing to zero (which would freeze a player's rating forever).
     * 0.083 is the value suggested in the TrueSkill paper.
     */
    public static final double TAU = 0.083;

    /* ---------------------------------------------------------------------
     * How much each in-match achievement contributes to a player's rating
     * change on top of win/loss. These weights are a gameplay tuning choice.
     * -------------------------------------------------------------------*/

    private static final double KILL_WEIGHT = 0.15;
    private static final double FINAL_KILL_WEIGHT = 0.35;
    private static final double BED_BREAK_WEIGHT = 0.40;
    private static final double DEATH_WEIGHT = -0.10;

    /* =====================================================================
     * Rating helpers
     * ===================================================================*/

    /**
     * The conservative rating: mu - 3 * sigma. A lower bound on true skill
     * with ~99.7% confidence, and the value that gets turned into points.
     *
     * @param mu    the player's skill mean
     * @param sigma the player's skill uncertainty
     * @return the conservative rating
     */
    public static double conservativeRating(double mu, double sigma) {
        return mu - (3.0 * sigma);
    }

    /**
     * Converts a player's belief into the integer "points" shown in chat and
     * on the leaderboard. Points are conservative rating scaled up and clamped
     * so they can never go negative.
     *
     * @param mu           the player's skill mean
     * @param sigma        the player's skill uncertainty
     * @param displayScale how many points one unit of conservative rating is worth
     * @return the displayed point value
     */
    public static int displayPoints(double mu, double sigma, double displayScale) {
        return Math.max(0, (int) Math.round(conservativeRating(mu, sigma) * displayScale));
    }

    /**
     * Inverse of {@link #displayPoints}: given a desired point value and a
     * player's current sigma, compute the mu that would display as those
     * points. Used by the inactivity decay to re-anchor a decaying player.
     *
     * @param points       the target displayed points
     * @param displayScale the points-per-conservative-rating scale
     * @param sigma        the player's current uncertainty
     * @return the mu needed to display the requested points
     */
    public static double muForPoints(double points, double displayScale, double sigma) {
        return (points / displayScale) + (3.0 * sigma);
    }

    /**
     * Experience bonus: a small fixed reward added to mu when a player finishes
     * a game, regardless of result. It shrinks as the player gains experience
     * so early games feel rewarding but veterans are ranked purely by skill.
     *
     * @param experience how many games this player has already finished
     * @return the bonus to add to mu, capped at 0.05
     */
    public static double experienceOffset(int experience) {
        return Math.min(0.05, 0.5 / (experience + 1));
    }

    /**
     * Dynamic variance inflation (the TAU step): bumps sigma up by tau before
     * the match update, modelling skill drift and preventing sigma from hitting
     * zero permanently.
     *
     * @param sigma the player's current uncertainty
     * @return the inflated sigma
     */
    public static double inflateVariance(double sigma) {
        return Math.sqrt((sigma * sigma) + (TAU * TAU));
    }

    /* =====================================================================
     * Match-level math
     * ===================================================================*/

    /**
     * The pre-computed Gaussian factor for one match. It is shared by every
     * player in the game, so we calculate it once and feed it to each player's
     * update.
     *
     * <p>Derivation (simplified): treat the two teams as two Gaussian "super
     * players" whose combined skill difference is teamMu and total uncertainty
     * is the sum of every player's variance plus the game-distance term. The
     * normalised difference {@code t} is fed into the mean update function
     * {@code V(t)} and the variance update function {@code W(t)}:</p>
     *
     * <pre>
     *   totalVariance = sigmaA^2 + sigmaB^2 + (nA + nB) * beta^2
     *   c             = sqrt(totalVariance)
     *   t             = (muA - muB) / c
     *   V(t)          = phi(t) / Phi(t)        (mean update)
     *   W(t)          = V(t) * (V(t) + t)      (variance update)
     * </pre>
     *
     * @param muA    sum of mu over team A
     * @param sigmaSqA sum of sigma^2 over team A
     * @param countA   number of players on team A
     * @param muB    sum of mu over team B
     * @param sigmaSqB sum of sigma^2 over team B
     * @param countB   number of players on team B
     * @return the shared match factor
     */
    public static MatchFactor computeMatchFactor(double muA, double sigmaSqA, int countA,
                                                 double muB, double sigmaSqB, int countB) {
        // Combined uncertainty of both teams plus the game-distance term scaled
        // by the number of players involved.
        double totalVariance = sigmaSqA + sigmaSqB + (countA + countB) * Math.pow(BETA, 2);
        double c = Math.sqrt(totalVariance);
        double deltaMu = muA - muB;

        // Normalise the observed outcome difference by the combined uncertainty.
        double t = deltaMu / c;

        // V appears in the numerator of the mean update, W in the variance update.
        double v = pdf(t) / cdf(t);
        double w = v * (v + t);

        return new MatchFactor(c, totalVariance, v, w);
    }

    /**
     * Per-player Bayesian update. Returns how much this player's mu and sigma
     * should change because of the match, including their personal achievement
     * bonus (kills, beds, deaths...).
     *
     * <p>The mean update is a fraction of the match {@code V} value scaled by
     * the player's share of the total variance:</p>
     * <pre>
     *   meanDelta_i      = (sigma_i^2 / c) * V
     *   varianceFactor_i = 1 - (sigma_i^2 / totalVariance) * W
     * </pre>
     * <p>The more uncertain we were about this player (bigger sigma_i^2), the
     * more a single game teaches us about them. Winners apply a positive mean
     * update, losers a negative one. A player who played well but still lost
     * keeps their achievement bonus, so good individual play is never fully
     * punished by the team result.</p>
     *
     * @param mu        the player's current skill mean
     * @param sigma     the player's current uncertainty
     * @param factor    the match factor computed by {@link #computeMatchFactor}
     * @param isWinner  true for winning-team players
     * @param kills     kills the player scored in this match
     * @param finalKills final kills the player scored
     * @param bedBreaks beds the player broke
     * @param deaths    deaths the player suffered
     * @return the signed total mean delta and the variance factor to apply
     */
    public static RatingUpdate computeRatingUpdate(double mu, double sigma, MatchFactor factor,
                                                   boolean isWinner, int kills, int finalKills,
                                                   int bedBreaks, int deaths) {
        double sigmaSquared = sigma * sigma;

        // Directional learning from the match outcome.
        double meanDelta = (sigmaSquared / factor.getC()) * factor.getV();

        // How much confidence this game gave us about this specific player.
        // Clamped at 0.1 so floating point can never make sigma grow.
        double varianceFactor = Math.max(0.1, 1.0 - (sigmaSquared / factor.getTotalVariance()) * factor.getW());

        // Achievement contribution (positive or negative), same for both teams.
        double statDelta = (kills * KILL_WEIGHT)
                + (finalKills * FINAL_KILL_WEIGHT)
                + (bedBreaks * BED_BREAK_WEIGHT)
                + (deaths * DEATH_WEIGHT);

        double signedDelta = isWinner ? (meanDelta + statDelta) : (-meanDelta + statDelta);

        return new RatingUpdate(signedDelta, varianceFactor);
    }

    /* =====================================================================
     * Shared value objects
     * ===================================================================*/

    /**
     * Immutable per-match coefficients shared by every player in one game.
     */
    public static final class MatchFactor {

        private final double c;
        private final double totalVariance;
        private final double v;
        private final double w;

        private MatchFactor(double c, double totalVariance, double v, double w) {
            this.c = c;
            this.totalVariance = totalVariance;
            this.v = v;
            this.w = w;
        }

        /** Normalisation factor (sqrt of the total variance). */
        public double getC() { return c; }

        /** Combined variance of both teams plus the game-distance term. */
        public double getTotalVariance() { return totalVariance; }

        /** V function value: drives the mean (mu) update. */
        public double getV() { return v; }

        /** W function value: drives the variance (sigma) update. */
        public double getW() { return w; }
    }

    /**
     * Immutable result of a single player's match update.
     */
    public static final class RatingUpdate {

        private final double meanDelta;
        private final double varianceFactor;

        private RatingUpdate(double meanDelta, double varianceFactor) {
            this.meanDelta = meanDelta;
            this.varianceFactor = varianceFactor;
        }

        /**
         * Total amount to add to mu. Already signed: positive for a good
         * outcome, negative for a bad one.
         */
        public double getMeanDelta() { return meanDelta; }

        /**
         * Factor to multiply sigma by (the caller applies sqrt to convert
         * variance back to a standard deviation).
         */
        public double getVarianceFactor() { return varianceFactor; }
    }

    /* =====================================================================
     * Statistical primitives
     * ===================================================================*/

    /**
     * Probability density function (phi) of the standard normal distribution.
     * The "bell curve" height at x.
     *
     * @param x input value
     * @return phi(x)
     */
    public static double pdf(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2.0 * Math.PI);
    }

    /**
     * Cumulative distribution function (Phi) of the standard normal
     * distribution. The probability that a N(0,1) draw is less than x, i.e.
     * the area under the bell curve to the left of x.
     *
     * @param x input value
     * @return Phi(x)
     */
    public static double cdf(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    /**
     * Gauss error function, erf(x), approximated with Abramowitz &amp; Stegun
     * formula 7.1.26. Accurate to about 1.5e-7, far more precision than a
     * rating system needs. Returns the probability that a random variable
     * normally distributed with mean 0 and variance 1/2 falls in [-x, x].
     *
     * @param z input value
     * @return erf(z)
     */
    public static double erf(double z) {
        // Rational approximation coefficients (A&S 7.1.26).
        double t = 1.0 / (1.0 + 0.5 * Math.abs(z));
        double ans = 1.0 - t * Math.exp(-z * z - 1.26551223 +
                t * (1.00002368 + t * (0.37409196 + t * (0.09678418 +
                        t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 +
                                t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
        // erf is an odd function: erf(-z) = -erf(z).
        return z >= 0 ? ans : -ans;
    }
}