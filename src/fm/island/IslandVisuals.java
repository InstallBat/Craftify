package fm.island;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.View;
import android.widget.SeekBar;

final class IslandVisuals {
    static final int FALLBACK=0xffbda9ff;

    static int accent(Bitmap cover) {
        if(cover==null || cover.getWidth()<1 || cover.getHeight()<1)return FALLBACK;
        Bitmap pixels=Bitmap.createScaledBitmap(cover,12,12,false);
        float[] sample=new float[3],best={260f,.38f,.95f};
        float score=0;
        for(int y=0;y<12;y++)for(int x=0;x<12;x++) {
            Color.colorToHSV(pixels.getPixel(x,y),sample);
            float power=sample[1]*sample[2];
            if(power>score) {score=power;best[0]=sample[0];best[1]=Math.min(.82f,sample[1]);}
        }
        if(pixels!=cover)pixels.recycle();
        return Color.HSVToColor(best);
    }

    static GradientDrawable glass(int tint,int radius,boolean wide) {
        int glow=Color.rgb(Color.red(tint)/6,Color.green(tint)/7,Color.blue(tint)/6);
        GradientDrawable panel=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            wide?new int[]{0xff030305,0xff08080b,glow}:new int[]{0xff030304,0xff09090c});
        panel.setCornerRadius(radius);
        panel.setStroke(1,Color.argb(155,Color.red(tint),Color.green(tint),Color.blue(tint)));
        return panel;
    }

    static final class Pulse extends View {
        private final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean playing;
        private int tint=FALLBACK;
        Pulse(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void display(boolean active,int color){playing=active;tint=color;invalidate();}
        @Override protected void onDraw(Canvas canvas){
            pen.setColor(tint);
            float mid=getHeight()/2f, cell=getWidth()/7f;
            double time=SystemClock.uptimeMillis()/125.0;
            for(int i=0;i<7;i++){
                float f=playing?(float)Math.abs(Math.sin(time*.63+i*1.39)):.16f;
                float h=mid*(.18f+.52f*f);
                float x=cell*(i+.5f);
                canvas.drawRoundRect(x-cell*.28f,mid-h,x+cell*.28f,mid+h,cell*.3f,cell*.3f,pen);
            }
            if(playing)postInvalidateDelayed(30);
        }
    }

    static final class Dots extends SeekBar {
        private final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);
        int tint=FALLBACK;
        Dots(Context context){super(context);setThumb(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));}
        @Override protected synchronized void onDraw(Canvas canvas){
            float left=getPaddingLeft(),span=getWidth()-left-getPaddingRight();
            if(span<=0)return;
            int steps=25;
            float cell=span/steps,y=getHeight()/2f;
            float radius=Math.min(cell*.36f,getResources().getDisplayMetrics().density*3.4f);
            int active=Math.round((steps-1)*getProgress()/(float)Math.max(1,getMax()));
            for(int i=0;i<steps;i++){
                pen.setColor(i==active?tint:i<active?0xffeeedf5:0xff4a4753);
                float x=left+cell*(i+.5f);
                canvas.drawRoundRect(x-radius,y-radius,x+radius,y+radius,radius,radius,pen);
            }
        }
    }
}

