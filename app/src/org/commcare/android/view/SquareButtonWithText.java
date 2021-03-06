package org.commcare.android.view;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.StateSet;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.commcare.dalvik.R;

/**
 * @author Daniel Luna (dluna@dimagi.com)
 */
public class SquareButtonWithText extends RelativeLayout {
    private SquareButton squareButton;
    private TextView textView;

    private static final int DEFAULT_TEXT_COLOR = R.color.cc_core_bg;

    public SquareButtonWithText(Context context) {
        super(context);
    }

    public SquareButtonWithText(Context context, AttributeSet attrs) {
        super(context, attrs);

        inflateAndExtractCustomParams(context, attrs);
    }

    public SquareButtonWithText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        inflateAndExtractCustomParams(context, attrs);
    }

    private void inflateAndExtractCustomParams(Context context, AttributeSet attrs) {
        inflate(context, R.layout.square_button_text, this);
        this.setClickable(true);

        TypedArray typedArray = context.obtainStyledAttributes(attrs, R.styleable.SquareButtonWithText);

        Drawable backgroundImg = typedArray.getDrawable(R.styleable.SquareButtonWithText_img);
        int backgroundColor = getResources().getColor(typedArray.getResourceId(R.styleable.SquareButtonWithText_backgroundcolor, android.R.color.transparent));
        String text = typedArray.getString(R.styleable.SquareButtonWithText_subtitle);
        int colorButtonText = typedArray.getResourceId(R.styleable.SquareButtonWithText_colorText, DEFAULT_TEXT_COLOR);

        typedArray.recycle();

        squareButton = (SquareButton) findViewById(R.id.square_button);
        textView = (TextView) findViewById(R.id.text_view);

        if (isInEditMode()) {
            setUI(R.color.cc_brand_color, getResources().getDrawable(R.drawable.barcode), "Your text goes here", colorButtonText);
        }

        setUI(backgroundColor, backgroundImg, text, colorButtonText);
    }

    private void setUI(int backgroundColor, Drawable backgroundImg, String text, int colorButtonText) {
        setColor(backgroundColor);
        setImage(backgroundImg);
        setText(text);
        setTextColor(colorButtonText);
    }

    public void setText(String text) {
        if (textView != null) {
            textView.setText(text);
        }
    }

    public void setImage(Drawable backgroundImg) {
        squareButton.setImageDrawable(backgroundImg);
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    public void setColor(int backgroundColor) {
        ColorDrawable colorDrawable = new ColorDrawable(backgroundColor);

        int color = ViewUtil.getColorDrawableColor(colorDrawable);

        float[] hsvOutput = new float[3];
        Color.colorToHSV(color, hsvOutput);

        hsvOutput[2] = (float) (hsvOutput[2] / 1.5);

        int selectedColor = Color.HSVToColor(hsvOutput);

        ColorDrawable pressedBackground = new ColorDrawable(selectedColor);

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, pressedBackground);
        sld.addState(StateSet.WILD_CARD, colorDrawable);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            this.setBackground(sld);
        } else {
            this.setBackgroundDrawable(sld);
        }
    }

    public void setTextColor(int textColor) {
        textView.setTextColor(getResources().getColor(textColor));
    }
}
