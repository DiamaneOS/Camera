package app.grapheneos.camera.capturer;

/** Optional device calibration: width,height,CFA,periodX,periodY,phaseX,phaseY. */
public final class RawCorrectionGrid {
    private RawCorrectionGrid() {}
    public static int[] select(String profile, int width, int height, int cfa) {
        if (profile == null || profile.length() > 96) return null;
        String[] values = profile.split(",", -1);
        if (values.length != 7) return null;
        int[] v = new int[7];
        try { for (int i=0;i<7;i++) v[i]=Integer.parseInt(values[i].trim()); }
        catch (NumberFormatException invalid) { return null; }
        if (v[0]!=width || v[1]!=height || v[2]!=cfa || cfa<0 || cfa>3 ||
                v[3]<4 || v[3]>32 || v[4]<4 || v[4]>32 || v[3]%2!=0 || v[4]%2!=0 ||
                v[5]<0 || v[5]>=v[3] || v[6]<0 || v[6]>=v[4]) return null;
        return new int[] {v[3],v[4],v[5],v[6]};
    }
}
