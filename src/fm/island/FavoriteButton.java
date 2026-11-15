package fm.island;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

final class FavoriteButton extends View {
    static final int REMOVE_DURATION=460;
    private static final int RED=0xffe04458, GRAY=0xff858591;
    private static final int[] SPARKS={0xffff7484,0xffffbd80,0xffec9aff,0xffffa5ae};
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path heart=new Path(),left=new Path(),right=new Path();
    private final ArgbEvaluator colors=new ArgbEvaluator();
    private ValueAnimator animation;
    private float phase=1;

    FavoriteButton(Context context){
        super(context);
        setClickable(true);
        setFocusable(true);
        heart.moveTo(0,13);
        heart.cubicTo(-5,8,-16,1,-16,-6);
        heart.cubicTo(-16,-14,-6,-17,0,-9);
        heart.cubicTo(6,-17,16,-14,16,-6);
        heart.cubicTo(16,1,5,8,0,13);
        heart.close();

        Path split=new Path();
        split.moveTo(-20,-20);
        split.lineTo(1,-20);
        split.lineTo(-2,-4);
        split.lineTo(2,0);
        split.lineTo(-2,5);
        split.lineTo(1,16);
        split.lineTo(-20,20);
        split.close();
        left.op(heart,split,Path.Op.INTERSECT);
        right.op(heart,split,Path.Op.DIFFERENCE);
    }

    void showFavorite(boolean active){
        if(animation!=null){animation.cancel();animation=null;}
        phase=1;
        setFavorite(active);
    }
    void animateFavorite(boolean active){
        showFavorite(active);
        phase=0;
        animation=ValueAnimator.ofFloat(0,1);
        animation.setDuration(active?390:REMOVE_DURATION);
        animation.addUpdateListener(value->{
            phase=(float)value.getAnimatedValue();
            invalidate();
        });
        animation.start();
    }
    private void setFavorite(boolean active){
        setSelected(active);
        setContentDescription(active?"Убрать из избранного":"В избранное");
        invalidate();
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Button");
    }
    @Override protected void onDetachedFromWindow(){
        showFavorite(isSelected());
        super.onDetachedFromWindow();
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        canvas.save();
        canvas.translate(getWidth()/2f,getHeight()/2f);
        float unit=Math.min(getWidth(),getHeight())/48f;
        canvas.scale(unit,unit);
        if(isSelected()){
            float pulse=(float)Math.sin(Math.PI*phase);
            paint.setColor(RED);
            canvas.save();
            canvas.scale(1+.13f*pulse,1+.13f*pulse);
            canvas.drawPath(heart,paint);
            canvas.restore();
            if(phase<1)drawSparks(canvas);
        }else if(phase<1){
            float spread=(float)Math.sin(Math.PI*phase);
            paint.setColor((int)colors.evaluate(phase,RED,GRAY));
            drawHalf(canvas,left,-spread);
            drawHalf(canvas,right,spread);
        }else{
            paint.setColor(GRAY);
            canvas.drawPath(heart,paint);
        }
        canvas.restore();
    }
    private void drawHalf(Canvas canvas,Path half,float spread){
        canvas.save();
        canvas.translate(spread*4,Math.abs(spread)*1.5f);
        canvas.rotate(spread*13);
        canvas.drawPath(half,paint);
        canvas.restore();
    }
    private void drawSparks(Canvas canvas){
        for(int i=0;i<8;i++){
            double angle=(i+.5)*Math.PI/4;
            float distance=14+phase*(i%2==0?8:6);
            paint.setColor(SPARKS[i%SPARKS.length]);
            paint.setAlpha(Math.round(255*(1-phase)));
            canvas.drawCircle((float)Math.cos(angle)*distance,(float)Math.sin(angle)*distance,
                (i%2==0?1.45f:1f)*(1-phase*.45f),paint);
        }
    }
}
