package work.nekow.particledrawing.core.easing;

/**
 * Cubic-bezier easing curve, compatible with CSS easing functions.
 * Defined by four control points where P0=(0,0) and P3=(1,1) are fixed.
 * P1=(x1,y1) and P2=(x2,y2) are user-defined in the [0,1] range.
 *
 * <p>Evaluation uses bisection with Newton-Raphson refinement for O(log N) precision.
 */
@SuppressWarnings("unused")
public final class EasingCurve {

    private static final double EPSILON = 1e-7;
    private static final int MAX_ITERATIONS = 20;

    private final double x1, y1, x2, y2;
    private final double[] sampleCache;

    public EasingCurve(double x1, double y1, double x2, double y2) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;

        this.sampleCache = new double[11];
        for (int i = 0; i <= 10; i++) {
            sampleCache[i] = sampleCurveX(i / 10.0);
        }
    }

    /**
     * Evaluates the easing curve at progress t in [0, 1].
     * Returns the eased output value in [0, 1].
     */
    public float evaluate(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return (float) sampleCurveY(solveTForX(t));
    }

    /**
     * Returns the control point values, suitable for serialization.
     */
    public double x1() { return x1; }
    public double y1() { return y1; }
    public double x2() { return x2; }
    public double y2() { return y2; }

    private double sampleCurveX(double t) {
        return ((1 - t) * (1 - t) * (1 - t) * 0)
             + 3 * (1 - t) * (1 - t) * t * x1
             + 3 * (1 - t) * t * t * x2
             + t * t * t * 1;
    }

    private double sampleCurveY(double t) {
        return ((1 - t) * (1 - t) * (1 - t) * 0)
             + 3 * (1 - t) * (1 - t) * t * y1
             + 3 * (1 - t) * t * t * y2
             + t * t * t * 1;
    }

    private double sampleCurveDerivativeX(double t) {
        return 3 * (1 - t) * (1 - t) * (x1 - 0)
             + 6 * (1 - t) * t * (x2 - x1)
             + 3 * t * t * (1 - x2);
    }

    private double solveTForX(double x) {
        double t = x;
        for (int i = 0; i < 8; i++) {
            double curX = sampleCurveX(t) - x;
            if (Math.abs(curX) < EPSILON) {
                return t;
            }
            double d = sampleCurveDerivativeX(t);
            if (Math.abs(d) < 1e-6) {
                break;
            }
            t = t - curX / d;
        }

        double t0 = 0;
        double t1 = 1;
        t = x;

        if (t < t0) return t0;
        if (t > t1) return t1;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double curX = sampleCurveX(t) - x;
            if (Math.abs(curX) < EPSILON) {
                return t;
            }
            if (curX > 0) {
                t1 = t;
            } else {
                t0 = t;
            }
            t = (t0 + t1) / 2.0;
        }

        return t;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EasingCurve c)) return false;
        return Double.compare(c.x1, x1) == 0
            && Double.compare(c.y1, y1) == 0
            && Double.compare(c.x2, x2) == 0
            && Double.compare(c.y2, y2) == 0;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(x1, y1, x2, y2);
    }

    @Override
    public String toString() {
        return String.format("cubic-bezier(%.3f, %.3f, %.3f, %.3f)", x1, y1, x2, y2);
    }

    public static EasingCurve fromCss(String css) {
        String inner = css.replace("cubic-bezier(", "").replace(")", "").trim();
        String[] parts = inner.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid CSS cubic-bezier: " + css);
        }
        return new EasingCurve(
            Double.parseDouble(parts[0].trim()),
            Double.parseDouble(parts[1].trim()),
            Double.parseDouble(parts[2].trim()),
            Double.parseDouble(parts[3].trim())
        );
    }
}
