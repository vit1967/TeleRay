/*
 * Custom cell for V2Ray action button with highlighting
 */

package org.telegram.ui.Cells;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import com.teleray.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

public class ActionButtonCell extends FrameLayout {

    private TextView textView;
    private boolean needDivider;
    private Paint dividerPaint;
    private Paint backgroundPaint;
    private int textColor;

    public ActionButtonCell(Context context) {
        super(context);

        setWillNotDraw(false);

        dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dividerPaint.setColor(Theme.getColor(Theme.key_windowBackgroundGrayShadow));

        backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);

        textView = new TextView(context);
        textView.setTextColor(textColor);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        textView.setTypeface(AndroidUtilities.getTypeface("fonts/rubik_medium.ttf"));
        textView.setLines(1);
        textView.setMaxLines(1);
        textView.setSingleLine(true);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setGravity(Gravity.CENTER);
        textView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), 
                       MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(50) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));
    }

    public void setText(String text, boolean divider) {
        textView.setText(text);
        needDivider = divider;
    }

    public void setTextColor(int color) {
        textView.setTextColor(color);
        textColor = color;
    }

    public void setBackgroundColor(int color) {
        backgroundPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Draw rounded rectangle background
        float radius = AndroidUtilities.dp(8);
        canvas.drawRoundRect(
            AndroidUtilities.dp(16), 
            AndroidUtilities.dp(8), 
            getMeasuredWidth() - AndroidUtilities.dp(16), 
            getMeasuredHeight() - AndroidUtilities.dp(8) - (needDivider ? 1 : 0),
            radius, radius, backgroundPaint
        );

        // Draw divider if needed
        if (needDivider) {
            canvas.drawLine(
                AndroidUtilities.dp(20), 
                getMeasuredHeight() - 1, 
                getMeasuredWidth() - AndroidUtilities.dp(20), 
                getMeasuredHeight() - 1, 
                dividerPaint
            );
        }
    }

    public TextView getTextView() {
        return textView;
    }
}
