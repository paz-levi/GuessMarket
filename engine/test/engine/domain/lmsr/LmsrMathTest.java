package engine.domain.lmsr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// Reproduces every number in docs-reference/lmsr-appendix.md's worked example (b=100, buying 100 YES shares from (0,0)).
class LmsrMathTest {

    private static final double LIQUIDITY_PARAMETER = 100;
    private static final double DELTA = 1e-9;

    // At the start of an event (0 shares bought either way), both options are priced at 50%.
    @Test
    void initialPricesAreEqualAt50Percent() {
        assertEquals(0.5, LmsrMath.price(0, 0, LIQUIDITY_PARAMETER), DELTA);
        assertEquals(69.31471805599453, LmsrMath.cost(0, 0, LIQUIDITY_PARAMETER), DELTA);
    }

    // The appendix's worked example: buying 100 YES shares from (0,0) with b=100 costs ~62 (appendix rounds; exact value verified via jshell).
    @Test
    void costOfBuying100YesSharesMatchesWorkedExample() {
        double cost = LmsrMath.purchaseCost(0, 0, LIQUIDITY_PARAMETER, 100);
        assertEquals(62.01145069582775, cost, DELTA);
    }

    // After buying 100 YES shares, YES is priced at ~0.731 and NO (its complement) at ~0.269 — matches the appendix's "always complements to 1" note.
    @Test
    void priceAfterBuying100YesSharesMatchesWorkedExample() {
        double priceYes = LmsrMath.price(100, 0, LIQUIDITY_PARAMETER);
        double priceNo = LmsrMath.price(0, 100, LIQUIDITY_PARAMETER);
        assertEquals(0.7310585786300049, priceYes, DELTA);
        assertEquals(0.2689414213699951, priceNo, DELTA);
    }
}
