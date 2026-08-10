package work.nekow.particledrawing.api;

/**
 * Immutable RGBA color for particles.
 * All components are in the [0, 1] range.
 * Designed for efficient use in rendering and network serialization.
 */
@SuppressWarnings("unused")
public final class Color {

    public static final Color WHITE       = new Color(1.0f, 1.0f, 1.0f, 1.0f);
    public static final Color BLACK       = new Color(0.0f, 0.0f, 0.0f, 1.0f);
    public static final Color RED         = new Color(1.0f, 0.0f, 0.0f, 1.0f);
    public static final Color GREEN       = new Color(0.0f, 1.0f, 0.0f, 1.0f);
    public static final Color BLUE        = new Color(0.0f, 0.0f, 1.0f, 1.0f);
    public static final Color YELLOW      = new Color(1.0f, 1.0f, 0.0f, 1.0f);
    public static final Color CYAN        = new Color(0.0f, 1.0f, 1.0f, 1.0f);
    public static final Color MAGENTA     = new Color(1.0f, 0.0f, 1.0f, 1.0f);
    public static final Color ORANGE      = new Color(1.0f, 0.5f, 0.0f, 1.0f);
    public static final Color TRANSPARENT = new Color(0.0f, 0.0f, 0.0f, 0.0f);

    private final float r, g, b, a;

    private Color(float r, float g, float b, float a) {
        this.r = clamp(r);
        this.g = clamp(g);
        this.b = clamp(b);
        this.a = clamp(a);
    }

    public static Color of(float r, float g, float b) {
        return new Color(r, g, b, 1.0f);
    }

    public static Color of(float r, float g, float b, float a) {
        return new Color(r, g, b, a);
    }

    public static Color ofInt(int r, int g, int b) {
        return new Color(r / 255f, g / 255f, b / 255f, 1.0f);
    }

    public static Color ofInt(int r, int g, int b, int a) {
        return new Color(r / 255f, g / 255f, b / 255f, a / 255f);
    }

    public static Color ofPacked(int abgr) {
        float a = ((abgr >> 24) & 0xFF) / 255f;
        float b = ((abgr >> 16) & 0xFF) / 255f;
        float g = ((abgr >> 8)  & 0xFF) / 255f;
        float r = (abgr         & 0xFF) / 255f;
        return new Color(r, g, b, a);
    }

    public static Color ofHsb(float hue, float saturation, float brightness) {
        int rgb = java.awt.Color.HSBtoRGB(hue % 1.0f, clamp(saturation), clamp(brightness));
        return new Color(
            ((rgb >> 16) & 0xFF) / 255f,
            ((rgb >> 8)  & 0xFF) / 255f,
            (rgb         & 0xFF) / 255f,
            1.0f
        );
    }

    public static Color ofHsb(float hue, float saturation, float brightness, float alpha) {
        Color c = ofHsb(hue, saturation, brightness);
        return new Color(c.r, c.g, c.b, alpha);
    }

    public float r() { return r; }
    public float g() { return g; }
    public float b() { return b; }
    public float a() { return a; }

    public int rInt() { return (int)(r * 255); }
    public int gInt() { return (int)(g * 255); }
    public int bInt() { return (int)(b * 255); }
    public int aInt() { return (int)(a * 255); }

    public Color withAlpha(float alpha) {
        return new Color(r, g, b, alpha);
    }

    public Color multiply(float factor) {
        return new Color(r * factor, g * factor, b * factor, a);
    }

    public Color lerp(Color target, float t) {
        t = clamp(t);
        return new Color(
            r + (target.r - r) * t,
            g + (target.g - g) * t,
            b + (target.b - b) * t,
            a + (target.a - a) * t
        );
    }

    public int packABGR() {
        int ai = clampToInt(a * 255);
        int bi = clampToInt(b * 255);
        int gi = clampToInt(g * 255);
        int ri = clampToInt(r * 255);
        return (ai << 24) | (bi << 16) | (gi << 8) | ri;
    }

    public int packARGB() {
        int ai = clampToInt(a * 255);
        int ri = clampToInt(r * 255);
        int gi = clampToInt(g * 255);
        int bi = clampToInt(b * 255);
        return (ai << 24) | (ri << 16) | (gi << 8) | bi;
    }

    public float luminance() {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    public boolean isOpaque() {
        return a >= 1.0f;
    }

    public boolean isTransparent() {
        return a <= 0.0f;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Color c)) return false;
        return Float.compare(c.r, r) == 0
            && Float.compare(c.g, g) == 0
            && Float.compare(c.b, b) == 0
            && Float.compare(c.a, a) == 0;
    }

    @Override
    public int hashCode() {
        int result = Float.hashCode(r);
        result = 31 * result + Float.hashCode(g);
        result = 31 * result + Float.hashCode(b);
        result = 31 * result + Float.hashCode(a);
        return result;
    }

    @Override
    public String toString() {
        return String.format("Color(r=%.3f, g=%.3f, b=%.3f, a=%.3f)", r, g, b, a);
    }

    private static float clamp(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private static int clampToInt(float v) {
        int i = (int) v;
        if (i < 0) return 0;
        if (i > 255) return 255;
        return i;
    }
}
