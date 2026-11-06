package cc.troikadumper;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * Two-faced card container: the first XML child is the front face (any view - currently a
 * FrameLayout with the artwork and the "tap to flip" hint), the second is the back
 * ({@link TroikaCardBackView}). A tap rotates the whole view 180 degrees around its vertical axis
 * with a real 3D perspective, so the card can be turned over like the physical one. The back face is
 * pre-rotated by 180 degrees, which keeps its text readable instead of mirrored.
 */
public class CardFlipView extends FrameLayout {

    /** Height / width of a Troika card (85.6 x 54 mm). */
    public static final float CARD_RATIO = 54f / 85.6f;

    /** Corner radius as a fraction of the width; matches a real card (about 3.2 mm of 85.6 mm). */
    protected static final float CORNER_RADIUS_RATIO = 0.037f;

    protected static final long FLIP_DURATION_MS = 450L;

    protected View front;
    protected View back;
    protected boolean flipped;
    protected ValueAnimator animator;

    public CardFlipView(Context context) {
        this(context, null);
    }

    public CardFlipView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CardFlipView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClickable(true);
        setFocusable(true);
        // Stronger perspective on denser screens; 12000dp-equivalent keeps the card undistorted.
        setCameraDistance(12000f * getResources().getDisplayMetrics().density);

        // The artwork has square canvas corners; clip to a real card outline and let the elevation
        // shadow follow it.
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        view.getWidth() * CORNER_RADIUS_RATIO);
            }
        });
        setElevation(4f * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() < 2) {
            throw new IllegalStateException("CardFlipView needs a front and a back child");
        }
        front = getChildAt(0);
        back = getChildAt(1);
        back.setRotationY(180f);
        back.setVisibility(INVISIBLE);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int width = (widthMode == MeasureSpec.UNSPECIFIED)
                ? getSuggestedMinimumWidth()
                : MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.round(width * CARD_RATIO);

        int childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int childHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(childWidth, childHeight);
        }
        setMeasuredDimension(width, height);
    }

    public boolean isFlipped() {
        return flipped;
    }

    /**
     * Turns the card over: front -> back or back -> front.
     */
    public void toggle() {
        setFlipped(!flipped);
    }

    public void setFlipped(boolean value) {
        setFlipped(value, true);
    }

    /**
     * @param value   true shows the back face.
     * @param animate false snaps instantly (used when restoring state).
     */
    public void setFlipped(boolean value, boolean animate) {
        flipped = value;
        float target = value ? 180f : 0f;

        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (!animate) {
            setRotationY(target);
            updateFaces(target);
            return;
        }

        animator = ValueAnimator.ofFloat(getRotationY(), target);
        animator.setDuration(FLIP_DURATION_MS);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float angle = (Float) animation.getAnimatedValue();
                setRotationY(angle);
                updateFaces(angle);
            }
        });
        animator.start();
    }

    /**
     * Shows the face that is turned towards the viewer. Both faces are edge-on at 90/270 degrees,
     * so switching exactly there keeps the animation seamless.
     */
    protected void updateFaces(float angle) {
        float normalized = ((angle % 360f) + 360f) % 360f;
        boolean showBack = normalized > 90f && normalized < 270f;
        front.setVisibility(showBack ? INVISIBLE : VISIBLE);
        back.setVisibility(showBack ? VISIBLE : INVISIBLE);
    }
}
