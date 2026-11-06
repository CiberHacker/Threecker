package cc.troikadumper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

/**
 * Draws the back artwork of the Troika card and prints the dump data into its white fields: the card
 * number goes into the long top field and the balance into the short bottom field. The square field
 * between them is left untouched on purpose.
 *
 * The field geometry is normalized against the source image (857 x 540), so the text stays aligned
 * at every screen width.
 */
public class TroikaCardBackView extends View {

    protected static final float NUMBER_LEFT = 0.0782f;
    protected static final float NUMBER_RIGHT = 0.4574f;
    protected static final float NUMBER_TOP = 0.1333f;
    protected static final float NUMBER_BOTTOM = 0.2481f;

    protected static final float BALANCE_LEFT = 0.0782f;
    protected static final float BALANCE_RIGHT = 0.4306f;
    protected static final float BALANCE_TOP = 0.6407f;
    protected static final float BALANCE_BOTTOM = 0.9037f;

    /** Text height as a fraction of the field height. */
    protected static final float TEXT_FILL = 0.62f;

    protected Bitmap card;
    protected final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final RectF destination = new RectF();
    protected String number = "";
    protected String amount = "";

    public TroikaCardBackView(Context context) {
        this(context, null);
    }

    public TroikaCardBackView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TroikaCardBackView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        card = BitmapFactory.decodeResource(getResources(), R.drawable.troika_card_back);
        ink.setColor(ContextCompat.getColor(context, R.color.card_ink));
        ink.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * @param cardNumber formatted card number, e.g. "0030 050 636".
     * @param amount     formatted balance, e.g. "430 ₽".
     */
    public void setCardData(String cardNumber, String amount) {
        this.number = (cardNumber == null) ? "" : cardNumber;
        this.amount = (amount == null) ? "" : amount;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) {
            return;
        }

        if (card != null) {
            destination.set(0, 0, width, height);
            canvas.drawBitmap(card, null, destination, null);
        }

        ink.setTypeface(Typeface.MONOSPACE);
        ink.setLetterSpacing(0.02f);
        drawInField(canvas, number,
                NUMBER_LEFT, NUMBER_RIGHT, NUMBER_TOP, NUMBER_BOTTOM);

        ink.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ink.setLetterSpacing(0f);
        drawInField(canvas, amount,
                BALANCE_LEFT, BALANCE_RIGHT, BALANCE_TOP, BALANCE_BOTTOM);
    }

    /**
     * Centers the text in one white field, shrinking it so it never overflows the field.
     */
    protected void drawInField(Canvas canvas, String text,
                               float left, float right, float top, float bottom) {
        if (text == null || text.length() == 0) {
            return;
        }
        float fieldWidth = (right - left) * getWidth();
        float fieldHeight = (bottom - top) * getHeight();
        float centerX = (left + right) * 0.5f * getWidth();
        float centerY = (top + bottom) * 0.5f * getHeight();

        float size = fieldHeight * TEXT_FILL;
        ink.setTextSize(size);
        float available = fieldWidth * 0.9f;
        float measured = ink.measureText(text);
        if (measured > available && measured > 0f) {
            ink.setTextSize(size * available / measured);
        }

        Paint.FontMetrics metrics = ink.getFontMetrics();
        float baseline = centerY - (metrics.ascent + metrics.descent) * 0.5f;
        canvas.drawText(text, centerX, baseline, ink);
    }
}
