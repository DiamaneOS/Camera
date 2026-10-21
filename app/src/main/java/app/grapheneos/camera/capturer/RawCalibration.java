package app.grapheneos.camera.capturer;

/** Camera2 dynamic black/noise arrays are already in CFA site order, not fixed RGB order. */
final class RawCalibration {
    static float[] encode(float[] black,float white,double[] noise) {
        if(black.length!=4 || noise.length!=8 || !Float.isFinite(white)) throw new IllegalArgumentException("Invalid RAW calibration");
        float[] result=new float[13];result[4]=white;
        for(int site=0;site<4;site++) {
            if(!Float.isFinite(black[site]) || black[site]<0 || black[site]>=white || !Double.isFinite(noise[site*2]) ||
                !Double.isFinite(noise[site*2+1]) || noise[site*2]<0 || noise[site*2+1]<0) throw new IllegalArgumentException("Invalid RAW site calibration");
            result[site]=black[site];result[5+site*2]=(float)noise[site*2];result[6+site*2]=(float)noise[site*2+1];
        }
        return result;
    }
}
