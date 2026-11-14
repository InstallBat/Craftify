package fm.island;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import java.io.IOException;

final class Backdrop extends Drawable {
    private final Bitmap bitmap;
    private final int darkness;
    private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);

    private Backdrop(Bitmap bitmap,int darkness){
        this.bitmap=bitmap;
        this.darkness=darkness;
    }

    static Backdrop load(Context context,String uri,int blur,int darkness) throws IOException {
        Uri address=Uri.parse(uri);
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inJustDecodeBounds=true;
        try(java.io.InputStream input=context.getContentResolver().openInputStream(address)){
            BitmapFactory.decodeStream(input,null,options);
        }
        int edge=Math.max(options.outWidth,options.outHeight),limit=blur>0?720:1600;
        options.inSampleSize=1;
        while(edge/options.inSampleSize>limit)options.inSampleSize*=2;
        options.inJustDecodeBounds=false;
        options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap image;
        try(java.io.InputStream input=context.getContentResolver().openInputStream(address)){
            image=BitmapFactory.decodeStream(input,null,options);
        }
        if(image==null)throw new IOException("Invalid background image");
        if(blur>0){
            Bitmap blurred=blur(image,Math.max(1,blur/3));
            image.recycle();
            image=blurred;
        }
        return new Backdrop(image,darkness);
    }

    private static Bitmap blur(Bitmap source,int radius){
        int w=source.getWidth(),h=source.getHeight();
        int[] pixels=new int[w*h], temporary=new int[w*h];
        source.getPixels(pixels,0,w,0,0,w,h);
        for(int pass=0;pass<2;pass++){
            average(pixels,temporary,w,h,1,w,radius);
            average(temporary,pixels,h,w,w,1,radius);
        }
        return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
    }
    private static void average(int[] input,int[] output,int length,int lines,int step,int stride,int radius){
        int count=2*radius+1;
        for(int line=0;line<lines;line++){
            int start=line*stride;
            int a=0,r=0,g=0,b=0;
            for(int x=-radius;x<=radius;x++){
                int p=input[start+Math.min(length-1,Math.max(0,x))*step];
                a+=p>>>24;r+=p>>16&255;g+=p>>8&255;b+=p&255;
            }
            for(int x=0;x<length;x++){
                output[start+x*step]=(a/count)<<24|(r/count)<<16|(g/count)<<8|b/count;
                int old=input[start+Math.max(0,x-radius)*step];
                int add=input[start+Math.min(length-1,x+radius+1)*step];
                a+=(add>>>24)-(old>>>24);r+=(add>>16&255)-(old>>16&255);
                g+=(add>>8&255)-(old>>8&255);b+=(add&255)-(old&255);
            }
        }
    }
    @Override public void draw(Canvas canvas){
        Rect bounds=getBounds();
        float scale=Math.max(bounds.width()/(float)bitmap.getWidth(),bounds.height()/(float)bitmap.getHeight());
        float w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;
        int save=canvas.save();
        canvas.clipRect(bounds);
        canvas.drawBitmap(bitmap,null,new RectF(bounds.exactCenterX()-w/2,bounds.exactCenterY()-h/2,
            bounds.exactCenterX()+w/2,bounds.exactCenterY()+h/2),paint);
        canvas.drawColor(Color.argb(darkness*255/100,0,0,0));
        canvas.restoreToCount(save);
    }
    @Override public void setAlpha(int value){paint.setAlpha(value);}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}


