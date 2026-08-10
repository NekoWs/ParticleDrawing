package work.nekow.particledrawing.core.easing;

import java.util.List;

/**
 * Named easing type, backed by a {@link EasingCurve}.
 * Predefined presets cover CSS-standard curves; use {@link #custom(double, double, double, double)}
 * for arbitrary cubic-bezier.
 */
@SuppressWarnings("unused")
public final class EasingType {

    private static final List<EasingType> PRESETS;
    private static final EasingType[] PRESET_ARRAY;

    public static final EasingType LINEAR;
    public static final EasingType EASE_IN;
    public static final EasingType EASE_OUT;
    public static final EasingType EASE_IN_OUT;
    public static final EasingType EASE_IN_QUAD;
    public static final EasingType EASE_OUT_QUAD;
    public static final EasingType EASE_IN_OUT_QUAD;
    public static final EasingType EASE_IN_CUBIC;
    public static final EasingType EASE_OUT_CUBIC;
    public static final EasingType EASE_IN_OUT_CUBIC;
    public static final EasingType EASE_IN_BOUNCE;
    public static final EasingType EASE_OUT_BOUNCE;
    public static final EasingType EASE_IN_ELASTIC;
    public static final EasingType EASE_OUT_ELASTIC;

    static {
        LINEAR            = new EasingType("LINEAR",            0, new EasingCurve(0.0,   0.0,   1.0,  1.0));
        EASE_IN          = new EasingType("EASE_IN",           1, new EasingCurve(0.42,  0.0,   1.0,  1.0));
        EASE_OUT         = new EasingType("EASE_OUT",          2, new EasingCurve(0.0,   0.0,   0.58, 1.0));
        EASE_IN_OUT      = new EasingType("EASE_IN_OUT",       3, new EasingCurve(0.42,  0.0,   0.58, 1.0));
        EASE_IN_QUAD     = new EasingType("EASE_IN_QUAD",      4, new EasingCurve(0.55,  0.085, 0.68, 0.53));
        EASE_OUT_QUAD    = new EasingType("EASE_OUT_QUAD",     5, new EasingCurve(0.25,  0.46,  0.45, 0.94));
        EASE_IN_OUT_QUAD = new EasingType("EASE_IN_OUT_QUAD",  6, new EasingCurve(0.455, 0.03,  0.515,0.955));
        EASE_IN_CUBIC    = new EasingType("EASE_IN_CUBIC",     7, new EasingCurve(0.55,  0.055, 0.675,0.19));
        EASE_OUT_CUBIC   = new EasingType("EASE_OUT_CUBIC",    8, new EasingCurve(0.215, 0.61,  0.355,1.0));
        EASE_IN_OUT_CUBIC= new EasingType("EASE_IN_OUT_CUBIC", 9, new EasingCurve(0.645, 0.045, 0.355,1.0));
        EASE_IN_BOUNCE   = new EasingType("EASE_IN_BOUNCE",   10, new EasingCurve(0.71,  0.01,  0.53, 1.61));
        EASE_OUT_BOUNCE  = new EasingType("EASE_OUT_BOUNCE",  11, new EasingCurve(0.29, -0.61,  0.47, 0.99));
        EASE_IN_ELASTIC  = new EasingType("EASE_IN_ELASTIC",  12, new EasingCurve(0.56,  0.01,  0.73, 1.61));
        EASE_OUT_ELASTIC = new EasingType("EASE_OUT_ELASTIC", 13, new EasingCurve(0.25, -0.61,  0.44, 0.99));

        PRESETS = List.of(
            LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT,
            EASE_IN_QUAD, EASE_OUT_QUAD, EASE_IN_OUT_QUAD,
            EASE_IN_CUBIC, EASE_OUT_CUBIC, EASE_IN_OUT_CUBIC,
            EASE_IN_BOUNCE, EASE_OUT_BOUNCE,
            EASE_IN_ELASTIC, EASE_OUT_ELASTIC
        );
        PRESET_ARRAY = PRESETS.toArray(new EasingType[0]);
    }

    private final String name;
    private final int ordinal;
    private final EasingCurve curve;

    private EasingType(String name, int ordinal, EasingCurve curve) {
        this.name = name;
        this.ordinal = ordinal;
        this.curve = curve;
    }

    public String name() { return name; }
    public int ordinal() { return ordinal; }
    public EasingCurve curve() { return curve; }

    public float evaluate(float t) {
        return curve.evaluate(t);
    }

    public static EasingType custom(double x1, double y1, double x2, double y2) {
        return new EasingType(null, -1, new EasingCurve(x1, y1, x2, y2));
    }

    public static List<EasingType> presets() {
        return PRESETS;
    }

    public boolean isPreset() {
        return ordinal >= 0;
    }

    /**
     * Finds a matching preset, or creates a custom instance.
     */
    public static EasingType fromCurve(double x1, double y1, double x2, double y2) {
        for (EasingType preset : PRESETS) {
            EasingCurve c = preset.curve();
            if (closeEnough(c.x1(), x1) && closeEnough(c.y1(), y1)
             && closeEnough(c.x2(), x2) && closeEnough(c.y2(), y2)) {
                return preset;
            }
        }
        return custom(x1, y1, x2, y2);
    }

    /**
     * Serializable form: [ordinal, x1, y1, x2, y2].
     * For presets, x1..y2 are zeroed; for custom, ordinal is -1.
     */
    public double[] serialize() {
        if (isPreset()) {
            return new double[]{(double) ordinal, 0, 0, 0, 0};
        }
        return new double[]{-1.0, curve.x1(), curve.y1(), curve.x2(), curve.y2()};
    }

    public static EasingType deserialize(double[] data) {
        int ordinal = (int) data[0];
        if (ordinal >= 0 && ordinal < PRESET_ARRAY.length) {
            return PRESET_ARRAY[ordinal];
        }
        return custom(data[1], data[2], data[3], data[4]);
    }

    private static boolean closeEnough(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EasingType that)) return false;
        return ordinal == that.ordinal && curve.equals(that.curve);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(ordinal, curve);
    }

    @Override
    public String toString() {
        if (isPreset()) return "EasingType." + name;
        return String.format("EasingType.cubic-bezier(%.2f,%.2f,%.2f,%.2f)",
            curve.x1(), curve.y1(), curve.x2(), curve.y2());
    }
}
