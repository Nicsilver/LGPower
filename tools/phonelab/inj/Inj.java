import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

public class Inj {
    static Object mgr;
    static Method inject;
    static int nargs;
    static long downTime;

    static void send(InputEvent e) throws Exception {
        if (nargs == 3) inject.invoke(mgr, e, 0, -1);
        else inject.invoke(mgr, e, 0);
    }

    // Every stamp is phone uptime: it is the clock the apps' LgpTest lines use, so no wall-clock conversion is needed on the phone.
    static long now() { return SystemClock.uptimeMillis(); }

    static void motion(int action, float x, float y) throws Exception {
        long t = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) downTime = t;
        MotionEvent e = MotionEvent.obtain(downTime, t, action, x, y, 1.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        send(e);
        e.recycle();
    }

    static void key(int code, int action) throws Exception {
        long t = SystemClock.uptimeMillis();
        KeyEvent e = new KeyEvent(t, t, action, code, 0);
        e.setSource(InputDevice.SOURCE_KEYBOARD);
        send(e);
    }

    public static void main(String[] a) throws Exception {
        Class<?> c;
        try { c = Class.forName("android.hardware.input.InputManagerGlobal"); mgr = c.getMethod("getInstance").invoke(null); }
        catch (Throwable t) { c = Class.forName("android.hardware.input.InputManager"); mgr = c.getMethod("getInstance").invoke(null); }
        for (Method m : c.getMethods()) if (m.getName().equals("injectInputEvent")) { inject = m; nargs = m.getParameterTypes().length; }
        System.out.println("READY " + c.getName() + " args=" + nargs);
        System.out.flush();
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = r.readLine()) != null) {
            String[] p = line.trim().split(" ");
            if (p.length == 0 || p[0].isEmpty()) continue;
            char cmd = p[0].charAt(0);
            long t0 = now();
            try {
                if (cmd == 'N') { System.out.println("N " + now() + " " + System.currentTimeMillis()); }
                else if (cmd == 'L') { // L <label...> : trial boundary line in the phone log
                    String label = line.trim().substring(1).trim().replace("\\", "\\\\").replace("\"", "\\\"");
                    android.util.Log.i("LgpRig", "{\"ev\":\"mark\",\"up\":" + now() + ",\"label\":\"" + label + "\"}");
                    System.out.println("L " + now());
                }
                else if (cmd == 'T') { // T x y [holdMs]
                    float x = Float.parseFloat(p[1]), y = Float.parseFloat(p[2]);
                    int hold = p.length > 3 ? Integer.parseInt(p[3]) : 40;
                    long d = now(); motion(MotionEvent.ACTION_DOWN, x, y);
                    Thread.sleep(hold);
                    long u = now(); motion(MotionEvent.ACTION_UP, x, y);
                    System.out.println("T " + d + " " + u);
                } else if (cmd == 'D') { float x = Float.parseFloat(p[1]), y = Float.parseFloat(p[2]); long d = now(); motion(MotionEvent.ACTION_DOWN, x, y); System.out.println("D " + d);
                } else if (cmd == 'M') { motion(MotionEvent.ACTION_MOVE, Float.parseFloat(p[1]), Float.parseFloat(p[2])); System.out.println("M " + now());
                } else if (cmd == 'U') { float x = Float.parseFloat(p[1]), y = Float.parseFloat(p[2]); long u = now(); motion(MotionEvent.ACTION_UP, x, y); System.out.println("U " + u);
                } else if (cmd == 'G') { // G x0 y0 x1 y1 durMs stepMs : down, linear moves, up; prints stamps
                    float x0 = Float.parseFloat(p[1]), y0 = Float.parseFloat(p[2]), x1 = Float.parseFloat(p[3]), y1 = Float.parseFloat(p[4]);
                    int dur = Integer.parseInt(p[5]), step = Integer.parseInt(p[6]);
                    long d = now(); motion(MotionEvent.ACTION_DOWN, x0, y0);
                    long start = System.nanoTime(); int n = 0; StringBuilder sb = new StringBuilder();
                    int steps = Math.max(1, dur / step);
                    for (int i = 1; i <= steps; i++) {
                        long due = start + (long) i * dur * 1000000L / steps;
                        long wait = due - System.nanoTime();
                        if (wait > 0) Thread.sleep(wait / 1000000L, (int) (wait % 1000000L));
                        float f = (float) i / steps;
                        motion(MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f); n++;
                        sb.append(' ').append(now() - d);
                    }
                    long u = now(); motion(MotionEvent.ACTION_UP, x1, y1);
                    System.out.println("G " + d + " " + u + " " + n + sb);
                } else if (cmd == 'H') { // H x y holdMs : press and hold
                    float x = Float.parseFloat(p[1]), y = Float.parseFloat(p[2]); int hold = Integer.parseInt(p[3]);
                    long d = now(); motion(MotionEvent.ACTION_DOWN, x, y);
                    Thread.sleep(hold);
                    long u = now(); motion(MotionEvent.ACTION_UP, x, y);
                    System.out.println("H " + d + " " + u);
                } else if (cmd == 'S') { // S gapMs holdMs x,y x,y ... : sequence of taps, gap = time between DOWNs
                    int gap = Integer.parseInt(p[1]), hold = Integer.parseInt(p[2]);
                    StringBuilder sb = new StringBuilder("S");
                    long start = System.nanoTime();
                    for (int i = 3; i < p.length; i++) {
                        long due = start + (long) (i - 3) * gap * 1000000L;
                        long wait = due - System.nanoTime();
                        if (wait > 0) Thread.sleep(wait / 1000000L, (int) (wait % 1000000L));
                        String[] xy = p[i].split(",");
                        float x = Float.parseFloat(xy[0]), y = Float.parseFloat(xy[1]);
                        long d = now(); motion(MotionEvent.ACTION_DOWN, x, y);
                        Thread.sleep(hold);
                        motion(MotionEvent.ACTION_UP, x, y);
                        sb.append(' ').append(d);
                    }
                    System.out.println(sb);
                } else if (cmd == 'K') { // K keycode
                    int code = Integer.parseInt(p[1]);
                    long d = now(); key(code, KeyEvent.ACTION_DOWN); key(code, KeyEvent.ACTION_UP); System.out.println("K " + d);
                } else if (cmd == 'Q') { return; }
            } catch (Throwable t) { System.out.println("ERR " + t); }
            System.out.flush();
        }
    }
}
