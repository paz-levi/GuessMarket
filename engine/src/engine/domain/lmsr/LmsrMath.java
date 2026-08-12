package engine.domain.lmsr;

// Pure LMSR (Logarithmic Market Scoring Rule) math for a 2-option event: no state, no dependency on Event or any other domain type.
public final class LmsrMath {

    private LmsrMath() {
    }

    // The LMSR cost function C(q1, q2) = b * ln(e^(q1/b) + e^(q2/b)) — the running "cost" of the market so far.
    public static double cost(double sharesOptionOne, double sharesOptionTwo, double liquidityParameter) {
        return liquidityParameter * Math.log(sumOfExponentials(sharesOptionOne, sharesOptionTwo, liquidityParameter));
    }

    // The current price of one option: its share of the total exponential weight, always in (0, 1).
    public static double price(double sharesOfOption, double sharesOfOtherOption, double liquidityParameter) {
        return Math.exp(sharesOfOption / liquidityParameter)
                / sumOfExponentials(sharesOfOption, sharesOfOtherOption, liquidityParameter);
    }

    // The cost to buy shareQuantity more shares of one option, as cost(after) - cost(before) — reuses cost(), never re-derives it.
    public static double purchaseCost(double sharesOfPurchasedOptionBefore, double sharesOfOtherOptionBefore,
                                       double liquidityParameter, double shareQuantity) {
        double costBefore = cost(sharesOfPurchasedOptionBefore, sharesOfOtherOptionBefore, liquidityParameter);
        double costAfter = cost(sharesOfPurchasedOptionBefore + shareQuantity, sharesOfOtherOptionBefore, liquidityParameter);
        return costAfter - costBefore;
    }

    // Shared e^(q1/b) + e^(q2/b) term used by both cost() and price(), factored out so it's computed the same way in exactly one place.
    private static double sumOfExponentials(double sharesOptionOne, double sharesOptionTwo, double liquidityParameter) {
        return Math.exp(sharesOptionOne / liquidityParameter) + Math.exp(sharesOptionTwo / liquidityParameter);
    }
}
