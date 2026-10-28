package com.nday.player;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** FrameLayout whose height follows its width at 16:9, so posters keep their shape at any column width. */
public class RatioFrameLayout extends FrameLayout {
    public RatioFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
    }
}
