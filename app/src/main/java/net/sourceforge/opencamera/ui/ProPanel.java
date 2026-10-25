package net.sourceforge.opencamera.ui;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.PreferenceKeys;
import net.sourceforge.opencamera.R;
import net.sourceforge.opencamera.cameracontroller.CameraController;
import net.sourceforge.opencamera.preview.Preview;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sony Xperia "Photo Pro" style control panel for pca-mera.
 *  Landscape: panel on the right, preview shrinks to fit beside it.
 *  Portrait: translucent panel above the bottom buttons.
 *  Tap SS / ISO / EV / FOCUS cells to choose what the dial (arrows) changes.
 */
public class ProPanel {
    private static final int ORANGE = Color.rgb(255, 106, 0);
    private static final int GREY = Color.rgb(150, 150, 150);
    private static final int LINE = Color.rgb(70, 70, 70);

    private static final int T_SS = 0;
    private static final int T_ISO = 1;
    private static final int T_EV = 2;
    private static final int T_FOCUS = 3;

    private static final int METER_MULTI = 0;
    private static final int METER_CENTER = 1;
    private static final int METER_SPOT = 2;
    private static final int METER_FACE = 3;
    private static final String[] METER_NAMES = {"MULTI", "CENTER", "SPOT", "FACE"};

    private static final String[] WB_VALUES = {"auto", "daylight", "cloudy-daylight", "shade", "twilight", "incandescent", "fluorescent", "warm-fluorescent"};
    private static final String[] WB_NAMES = {"AWB", "DAYLIGHT", "CLOUDY", "SHADE", "TWILIGHT", "INCAND", "FLUOR", "WARM FL"};

    private static final int[] SHUTTER_DENOMS = {
            8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320,
            250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3
    };
    private static final int[] ISO_STEPS = {50, 100, 200, 400, 800, 1600, 3200, 6400};

    private static final String PHOTO_MODE_HDR = "preference_photo_mode_hdr";
    private static final String PHOTO_MODE_STD = "preference_photo_mode_std";

    private final MainActivity main_activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;

    private LinearLayout root, cells_box, nav_row, shutter_row;
    private ScrollView scroll;
    private final ArrayList<LinearLayout> cell_rows = new ArrayList<>();
    private final ArrayList<TextView> cells = new ArrayList<>();
    private TextView mode_cell, ss_cell, iso_cell, ev_cell, focus_cell, meter_cell, wb_cell, hdr_cell, flash_cell, flip_cell;
    private TextView dial_label, dial_value, ael_button, lock_text;

    private boolean locked = false;
    private boolean running = false;
    private int target = -1; // -1 = pick a sensible default for the mode
    private int meter_mode = METER_MULTI;
    private float focus_dist = 0.0f;
    private Boolean landscape_applied = null;
    private int panel_width_applied = -1;
    private boolean error_shown = false;
    private static boolean started_toast_shown = false;

    private final Runnable refresh_runnable = new Runnable() {
        @Override
        public void run() {
            if( !running )
                return;
            refresh();
            handler.postDelayed(this, 400);
        }
    };

    public ProPanel(MainActivity main_activity) {
        this.main_activity = main_activity;
        this.density = main_activity.getResources().getDisplayMetrics().density;
        build();
        // tell the preview how much width the panel takes, before the camera is opened,
        // so that "fill screen" mode fills only the area beside the panel
        Preview preview = main_activity.getPreview();
        if( preview != null )
            preview.setReservedWidth(computePanelWidth(main_activity.getResources().getDisplayMetrics()));
    }

    /** Width of the right-hand panel in pixels (landscape). Leaves a 4:3 area for the viewfinder when possible. */
    private int computePanelWidth(DisplayMetrics dm) {
        int w = Math.max(dm.widthPixels, dm.heightPixels);
        int h = Math.min(dm.widthPixels, dm.heightPixels);
        int panel_width = w - (h * 4 / 3);
        return Math.max(dp(240), Math.min(panel_width, (int)(w * 0.45f)));
    }

    public void start() {
        if( !started_toast_shown ) {
            started_toast_shown = true;
            Toast.makeText(main_activity, "pca-mera: Photo Pro panel v6 loaded", Toast.LENGTH_LONG).show();
        }
        running = true;
        handler.removeCallbacks(refresh_runnable);
        handler.post(refresh_runnable);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(refresh_runnable);
    }

    private int dp(int v) {
        return (int)(v * density + 0.5f);
    }

    // ---------------------------------------------------------------- building the views

    private TextView makeCell(String text, int size_sp) {
        TextView tv = new TextView(main_activity);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(size_sp);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setPadding(dp(2), dp(7), dp(2), dp(7));
        return tv;
    }

    private void setBorder(TextView tv, int colour) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setStroke(dp(1), colour);
        tv.setBackground(d);
    }

    private LinearLayout makeRow() {
        LinearLayout row = new LinearLayout(main_activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(5);
        row.setLayoutParams(lp);
        return row;
    }

    private void addWeighted(LinearLayout row, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        row.addView(v, lp);
    }

    private TextView makeButton(String text, View.OnClickListener l) {
        TextView tv = makeCell(text, 13);
        setBorder(tv, GREY);
        tv.setOnClickListener(l);
        return tv;
    }

    private TextView addCell(String text) {
        TextView tv = makeCell(text, 11);
        setBorder(tv, LINE);
        cells.add(tv);
        return tv;
    }

    private void build() {
        root = new LinearLayout(main_activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(8), dp(4), dp(8), dp(4));
        root.setClickable(true); // consume touches so nothing behind the panel is triggered

        // --- dial
        dial_label = makeCell("SS", 11);
        dial_label.setTextColor(ORANGE);
        dial_label.setPadding(0, dp(2), 0, 0);
        root.addView(dial_label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout dial_row = makeRow();
        TextView dial_prev = makeCell("\u25C0", 18);
        dial_prev.setOnClickListener(v -> stepDial(-1));
        dial_value = makeCell("--", 22);
        dial_value.setTextColor(ORANGE);
        dial_value.setTypeface(Typeface.DEFAULT_BOLD);
        TextView dial_next = makeCell("\u25B6", 18);
        dial_next.setOnClickListener(v -> stepDial(1));
        dial_row.addView(dial_prev, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
        dial_row.addView(dial_value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dial_row.addView(dial_next, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(dial_row);

        // --- AF-ON / AEL / LOCK
        LinearLayout af_row = makeRow();
        TextView af_on = makeButton("AF-ON", v -> {
            if( locked )
                return;
            Preview preview = main_activity.getPreview();
            if( preview != null )
                preview.requestAutoFocus();
        });
        ael_button = makeButton("AEL", v -> {
            if( locked )
                return;
            Preview preview = main_activity.getPreview();
            if( preview != null && preview.supportsExposureLock() )
                preview.toggleExposureLock();
        });
        lock_text = makeButton("LOCK", v -> {
            locked = !locked;
            refresh();
        });
        addWeighted(af_row, af_on);
        addWeighted(af_row, ael_button);
        addWeighted(af_row, lock_text);
        root.addView(af_row);

        // --- setting cells (laid out into rows by layoutCells())
        cells_box = new LinearLayout(main_activity);
        cells_box.setOrientation(LinearLayout.VERTICAL);
        root.addView(cells_box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mode_cell = addCell("P");
        mode_cell.setTextSize(15);
        mode_cell.setTypeface(Typeface.DEFAULT_BOLD);
        mode_cell.setOnClickListener(v -> toggleMode());

        ss_cell = addCell("SS");
        ss_cell.setOnClickListener(v -> selectTarget(T_SS));

        iso_cell = addCell("ISO");
        iso_cell.setOnClickListener(v -> selectTarget(T_ISO));

        ev_cell = addCell("EV");
        ev_cell.setOnClickListener(v -> selectTarget(T_EV));
        ev_cell.setOnLongClickListener(v -> {
            resetEV();
            return true;
        });

        focus_cell = addCell("AF");
        focus_cell.setOnClickListener(v -> toggleFocus());

        meter_cell = addCell("MULTI");
        meter_cell.setOnClickListener(v -> cycleMeter());

        wb_cell = addCell("AWB");
        wb_cell.setOnClickListener(v -> cycleWhiteBalance());
        wb_cell.setOnLongClickListener(v -> {
            toggleWhiteBalanceLock();
            return true;
        });

        hdr_cell = addCell("HDR");
        hdr_cell.setOnClickListener(v -> toggleHDR());

        flash_cell = addCell("FLASH");
        flash_cell.setOnClickListener(v -> {
            if( locked )
                return;
            Preview preview = main_activity.getPreview();
            if( preview != null && preview.getSupportedFlashValues() != null )
                preview.cycleFlash(false, true);
        });

        flip_cell = addCell("FLIP");
        flip_cell.setOnClickListener(v -> {
            if( !locked )
                main_activity.clickedSwitchCamera(v);
        });

        // --- navigation row (landscape only: in portrait Open Camera's own buttons are visible)
        nav_row = makeRow();
        TextView menu = makeButton("MENU", v -> {
            if( !locked )
                main_activity.clickedSettings(v);
        });
        TextView popup = makeButton("OPT", v -> {
            if( !locked )
                main_activity.clickedPopupSettings(v);
        });
        TextView gallery = makeButton("GAL", v -> main_activity.clickedGallery(v));
        TextView video = makeButton("VID", v -> {
            if( !locked )
                main_activity.clickedSwitchVideo(v);
        });
        addWeighted(nav_row, menu);
        addWeighted(nav_row, popup);
        addWeighted(nav_row, gallery);
        addWeighted(nav_row, video);
        root.addView(nav_row);

        // --- shutter (landscape only)
        shutter_row = makeRow();
        shutter_row.setGravity(Gravity.CENTER);
        TextView shutter = makeCell("\u25CF", 26);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.WHITE);
        shutter.setBackground(ring);
        shutter.setTextColor(Color.BLACK);
        shutter.setOnClickListener(v -> {
            if( !locked )
                main_activity.takePicture(false);
        });
        shutter_row.addView(shutter, new LinearLayout.LayoutParams(dp(56), dp(56)));
        root.addView(shutter_row);
    }

    /** Re-flows the setting cells into rows of the given number of columns. */
    private void layoutCells(int cols) {
        for(LinearLayout r : cell_rows)
            r.removeAllViews();
        cells_box.removeAllViews();
        cell_rows.clear();
        LinearLayout row = null;
        int n = 0;
        for(TextView c : cells) {
            if( n % cols == 0 ) {
                row = makeRow();
                cells_box.addView(row);
                cell_rows.add(row);
            }
            addWeighted(row, c);
            n++;
        }
        while( row != null && n % cols != 0 ) {
            addWeighted(row, new View(main_activity));
            n++;
        }
    }

    // ---------------------------------------------------------------- attaching to the screen

    private void applyLayout() {
        DisplayMetrics dm = main_activity.getResources().getDisplayMetrics();
        boolean landscape = dm.widthPixels > dm.heightPixels;
        View preview_view = main_activity.findViewById(R.id.preview);
        View anchor = main_activity.findViewById(R.id.sliders_container);
        if( preview_view == null || anchor == null || !(anchor.getParent() instanceof ViewGroup) )
            return;
        ViewGroup parent = (ViewGroup)anchor.getParent();

        if( scroll == null ) {
            scroll = new ScrollView(main_activity);
            scroll.setFillViewport(false);
            scroll.addView(root, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // insert just before the sliders, so popups and sliders still draw above the panel
            int index = parent.indexOfChild(anchor);
            parent.addView(scroll, index, new RelativeLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        int panel_width = landscape ? computePanelWidth(dm) : 0;

        if( landscape_applied == null || landscape != landscape_applied.booleanValue() || panel_width != panel_width_applied ) {
            RelativeLayout.LayoutParams lp;
            if( landscape ) {
                lp = new RelativeLayout.LayoutParams(panel_width, ViewGroup.LayoutParams.MATCH_PARENT);
                lp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
                lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                scroll.setBackgroundColor(Color.BLACK);
                root.setBackgroundColor(Color.BLACK);
            }
            else {
                lp = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                lp.bottomMargin = dp(135); // keeps Open Camera's own bottom buttons visible
                scroll.setBackgroundColor(Color.argb(205, 0, 0, 0));
                root.setBackgroundColor(Color.TRANSPARENT);
            }
            scroll.setLayoutParams(lp);
            scroll.setVisibility(View.VISIBLE);
            nav_row.setVisibility(landscape ? View.VISIBLE : View.GONE);
            shutter_row.setVisibility(landscape ? View.VISIBLE : View.GONE);
            layoutCells(landscape ? 4 : 5);

            ViewGroup.LayoutParams vlp = preview_view.getLayoutParams();
            if( vlp instanceof ViewGroup.MarginLayoutParams ) {
                ((ViewGroup.MarginLayoutParams)vlp).rightMargin = landscape ? panel_width : 0;
                preview_view.setLayoutParams(vlp);
            }
            // RelativeLayout centres the view on the whole screen and ignores the margin when doing so,
            // so shift it left by half the panel width to centre it in the area beside the panel
            preview_view.setTranslationX(landscape ? -panel_width / 2.0f : 0.0f);
            landscape_applied = landscape;
            panel_width_applied = panel_width;
        }
    }

    // ---------------------------------------------------------------- state

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(main_activity);
    }

    private boolean isManual(SharedPreferences sp) {
        String iso = sp.getString(PreferenceKeys.ISOPreferenceKey, CameraController.ISO_DEFAULT);
        return !iso.equals(CameraController.ISO_DEFAULT);
    }

    private boolean isManualFocus(Preview preview) {
        String f = preview.getCurrentFocusValue();
        return f != null && f.equals("focus_mode_manual2");
    }

    private boolean isHDR(SharedPreferences sp) {
        return PHOTO_MODE_HDR.equals(sp.getString(PreferenceKeys.PhotoModePreferenceKey, PHOTO_MODE_STD));
    }

    /** The parameter the dial currently changes, falling back to a valid one. */
    private int effectiveTarget(boolean manual, boolean manual_focus) {
        int t = target;
        if( (t == T_SS || t == T_ISO) && !manual )
            t = -1;
        if( t == T_FOCUS && !manual_focus )
            t = -1;
        if( t == -1 )
            t = manual ? T_SS : T_EV;
        return t;
    }

    private long currentExposureTime(SharedPreferences sp, boolean manual, Preview preview) {
        if( manual )
            return sp.getLong(PreferenceKeys.ExposureTimePreferenceKey, CameraController.EXPOSURE_TIME_DEFAULT);
        CameraController cc = preview.getCameraController();
        if( cc != null && cc.captureResultHasExposureTime() )
            return cc.captureResultExposureTime();
        return -1;
    }

    private int currentISO(SharedPreferences sp, boolean manual, Preview preview) {
        if( manual )
            return preview.parseManualISOValue(sp.getString(PreferenceKeys.ISOPreferenceKey, CameraController.ISO_DEFAULT));
        CameraController cc = preview.getCameraController();
        if( cc != null && cc.captureResultHasIso() )
            return cc.captureResultIso();
        return -1;
    }

    private String shutterString(long ns) {
        if( ns <= 0 )
            return "--";
        if( ns >= 1000000000L / 2 ) {
            double s = ns / 1.0e9;
            return (Math.round(s * 10) / 10.0) + "\"";
        }
        return "1/" + Math.round(1.0e9 / ns);
    }

    private String evString(Preview preview) {
        String s = preview.getExposureCompensationString(preview.getCurrentExposure());
        Matcher m = Pattern.compile("([+-]\\d+([.,]\\d+)?)").matcher(s);
        if( m.find() )
            return m.group(1);
        return "0.0";
    }

    private String focusString() {
        if( focus_dist <= 0.0f )
            return "INF";
        return String.format(Locale.US, "%.1fm", 1.0f / focus_dist);
    }

    private String flashString(Preview preview) {
        if( preview.getSupportedFlashValues() == null )
            return "--";
        String f = preview.getCurrentFlashValue();
        if( f == null )
            return "--";
        switch( f ) {
            case "flash_off": return "OFF";
            case "flash_auto": return "AUTO";
            case "flash_on": return "ON";
            case "flash_torch": return "TORCH";
            case "flash_red_eye": return "RED";
            default: return "FLASH";
        }
    }

    private String wbString() {
        String wb = main_activity.getApplicationInterface().getWhiteBalancePref();
        if( wb == null )
            return "AWB";
        for(int i = 0; i < WB_VALUES.length; i++) {
            if( WB_VALUES[i].equals(wb) )
                return WB_NAMES[i];
        }
        return wb.toUpperCase(Locale.US);
    }

    private void styleCell(TextView tv, boolean selected, boolean enabled, boolean active) {
        tv.setTextColor(!enabled ? GREY : (active ? ORANGE : Color.WHITE));
        setBorder(tv, selected ? ORANGE : LINE);
    }

    private void refresh() {
        try {
            refreshInner();
        }
        catch(Throwable e) {
            if( !error_shown ) {
                error_shown = true;
                Toast.makeText(main_activity, "Photo Pro panel error: " + e, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void refreshInner() {
        applyLayout();
        Preview preview = main_activity.getPreview();
        if( preview == null || root == null )
            return;
        SharedPreferences sp = prefs();
        boolean manual = isManual(sp);
        boolean mf = isManualFocus(preview);
        int t = effectiveTarget(manual, mf);

        // dial
        switch( t ) {
            case T_SS:
                dial_label.setText("SHUTTER SPEED");
                dial_value.setText(shutterString(currentExposureTime(sp, true, preview)));
                break;
            case T_ISO:
                dial_label.setText("ISO");
                dial_value.setText(String.valueOf(currentISO(sp, true, preview)));
                break;
            case T_FOCUS:
                dial_label.setText("FOCUS");
                dial_value.setText(focusString());
                break;
            default:
                dial_label.setText("EXPOSURE VALUE");
                dial_value.setText(preview.supportsExposures() ? evString(preview) : "--");
                break;
        }

        // cells
        mode_cell.setText(manual ? "M" : "P");
        styleCell(mode_cell, false, true, manual);

        ss_cell.setText("SS " + shutterString(currentExposureTime(sp, manual, preview)));
        styleCell(ss_cell, t == T_SS, manual, false);

        int iso = currentISO(sp, manual, preview);
        iso_cell.setText("ISO " + (iso > 0 ? String.valueOf(iso) : "AUTO"));
        styleCell(iso_cell, t == T_ISO, manual, false);

        ev_cell.setText(preview.supportsExposures() ? "EV " + evString(preview) : "EV --");
        styleCell(ev_cell, t == T_EV, preview.supportsExposures(), false);

        focus_cell.setText(mf ? "MF" : "AF");
        styleCell(focus_cell, t == T_FOCUS, preview.supportsFocus(), mf);

        meter_cell.setText(METER_NAMES[meter_mode]);
        styleCell(meter_cell, false, true, meter_mode != METER_MULTI);

        wb_cell.setText(wbString());
        boolean awb_lock = preview.supportsWhiteBalanceLock() && preview.isWhiteBalanceLocked();
        styleCell(wb_cell, false, preview.getSupportedWhiteBalances() != null, awb_lock);

        boolean hdr = isHDR(sp);
        hdr_cell.setText(hdr ? "HDR ON" : "HDR");
        styleCell(hdr_cell, false, main_activity.supportsHDR(), hdr);

        flash_cell.setText(flashString(preview));
        styleCell(flash_cell, false, preview.getSupportedFlashValues() != null, false);

        styleCell(flip_cell, false, true, false);

        boolean ael = preview.supportsExposureLock() && preview.isExposureLocked();
        ael_button.setTextColor(ael ? ORANGE : Color.WHITE);
        setBorder(ael_button, ael ? ORANGE : GREY);

        lock_text.setText(locked ? "LOCKED" : "LOCK");
        lock_text.setTextColor(locked ? ORANGE : Color.WHITE);
        root.setAlpha(locked ? 0.7f : 1.0f);
    }

    // ---------------------------------------------------------------- actions

    private void selectTarget(int t) {
        if( locked )
            return;
        Preview preview = main_activity.getPreview();
        if( preview == null )
            return;
        SharedPreferences sp = prefs();
        boolean manual = isManual(sp);
        if( (t == T_SS || t == T_ISO) && !manual )
            return; // only in M mode
        if( t == T_FOCUS && !isManualFocus(preview) )
            return;
        target = t;
        refresh();
    }

    private void toggleMode() {
        if( locked )
            return;
        Preview preview = main_activity.getPreview();
        if( preview == null )
            return;
        SharedPreferences sp = prefs();
        SharedPreferences.Editor editor = sp.edit();
        if( isManual(sp) ) {
            // back to P (auto)
            editor.putString(PreferenceKeys.ISOPreferenceKey, CameraController.ISO_DEFAULT);
            editor.putLong(PreferenceKeys.ExposureTimePreferenceKey, CameraController.EXPOSURE_TIME_DEFAULT);
            editor.apply();
            target = -1;
            main_activity.updateForSettings(true, "");
        }
        else if( preview.supportsISORange() ) {
            // to M, keeping the current ISO and shutter speed if known
            int iso = 800;
            long exposure_time = CameraController.EXPOSURE_TIME_DEFAULT;
            CameraController cc = preview.getCameraController();
            if( cc != null && cc.captureResultHasIso() )
                iso = cc.captureResultIso();
            if( cc != null && cc.captureResultHasExposureTime() )
                exposure_time = cc.captureResultExposureTime();
            editor.putString(PreferenceKeys.ISOPreferenceKey, String.valueOf(iso));
            editor.putLong(PreferenceKeys.ExposureTimePreferenceKey, exposure_time);
            editor.apply();
            target = T_SS;
            main_activity.updateForSettings(true, "");
        }
        else {
            Toast.makeText(main_activity, "Manual mode not supported on this camera", Toast.LENGTH_SHORT).show();
        }
        refresh();
    }

    private void stepDial(int direction) {
        if( locked )
            return;
        Preview preview = main_activity.getPreview();
        if( preview == null )
            return;
        SharedPreferences sp = prefs();
        boolean manual = isManual(sp);
        switch( effectiveTarget(manual, isManualFocus(preview)) ) {
            case T_SS:
                stepShutter(preview, sp, direction);
                break;
            case T_ISO:
                stepISO(preview, sp, direction);
                break;
            case T_FOCUS:
                stepFocus(preview, direction);
                break;
            default:
                if( preview.supportsExposures() )
                    preview.setExposure(preview.getCurrentExposure() + direction * evUnits(preview));
                break;
        }
        refresh();
    }

    private void stepShutter(Preview preview, SharedPreferences sp, int direction) {
        if( !preview.supportsExposureTime() )
            return;
        long current = currentExposureTime(sp, true, preview);
        int best = 0;
        long best_diff = Long.MAX_VALUE;
        for(int i = 0; i < SHUTTER_DENOMS.length; i++) {
            long ns = 1000000000L / SHUTTER_DENOMS[i];
            long diff = Math.abs(ns - current);
            if( diff < best_diff ) {
                best_diff = diff;
                best = i;
            }
        }
        // list index 0 = fastest; the right arrow (+1) means a faster shutter
        int new_index = best - direction;
        if( new_index < 0 )
            new_index = 0;
        if( new_index >= SHUTTER_DENOMS.length )
            new_index = SHUTTER_DENOMS.length - 1;
        preview.setExposureTime(1000000000L / SHUTTER_DENOMS[new_index]);
    }

    private void stepISO(Preview preview, SharedPreferences sp, int direction) {
        if( !preview.supportsISORange() )
            return;
        int current = currentISO(sp, true, preview);
        int new_iso = current;
        if( direction > 0 ) {
            for(int i = 0; i < ISO_STEPS.length; i++) {
                if( ISO_STEPS[i] > current ) {
                    new_iso = ISO_STEPS[i];
                    break;
                }
            }
        }
        else {
            for(int i = ISO_STEPS.length - 1; i >= 0; i--) {
                if( ISO_STEPS[i] < current ) {
                    new_iso = ISO_STEPS[i];
                    break;
                }
            }
        }
        preview.setISO(new_iso);
    }

    private void stepFocus(Preview preview, int direction) {
        float min = preview.getMinimumFocusDistance();
        if( min <= 0.0f )
            return;
        // right arrow moves the focus further away (towards infinity)
        focus_dist -= direction * (min / 16.0f);
        if( focus_dist < 0.0f )
            focus_dist = 0.0f;
        if( focus_dist > min )
            focus_dist = min;
        preview.setFocusDistance(focus_dist, false, false);
    }

    /** Number of camera exposure steps that make up 1/3 EV (at least 1). */
    private int evUnits(Preview preview) {
        float step = preview.getExposureStep();
        if( step <= 0.0f )
            return 1;
        return Math.max(1, Math.round((1.0f / 3.0f) / step));
    }

    private void resetEV() {
        if( locked )
            return;
        Preview preview = main_activity.getPreview();
        if( preview != null && preview.supportsExposures() )
            preview.setExposure(0);
        refresh();
    }

    private void toggleFocus() {
        if( locked )
            return;
        Preview preview = main_activity.getPreview();
        if( preview == null || !preview.supportsFocus() )
            return;
        List<String> values = preview.getSupportedFocusValues();
        if( values == null )
            return;
        if( isManualFocus(preview) ) {
            if( target != T_FOCUS ) {
                target = T_FOCUS; // first tap selects the dial, second tap goes back to AF
            }
            else {
                String af = values.contains("focus_mode_continuous_picture") ? "focus_mode_continuous_picture" : "focus_mode_auto";
                preview.updateFocus(af, false, true);
                target = -1;
            }
        }
        else if( values.contains("focus_mode_manual2") ) {
            preview.updateFocus("focus_mode_manual2", false, true);
            preview.setFocusDistance(focus_dist, false, false);
            target = T_FOCUS;
        }
        else {
            Toast.makeText(main_activity, "Manual focus not supported on this camera", Toast.LENGTH_SHORT).show();
        }
        refresh();
    }

    private void cycleMeter() {
        if( locked )
            return;
        Preview preview = main_activity.getPreview();
        if( preview == null )
            return;
        int mode = (meter_mode + 1) % METER_NAMES.length;
        SharedPreferences sp = prefs();
        boolean face_pref = sp.getBoolean(PreferenceKeys.FaceDetectionPreferenceKey, false);
        boolean want_face = mode == METER_FACE;
        if( want_face != face_pref ) {
            sp.edit().putBoolean(PreferenceKeys.FaceDetectionPreferenceKey, want_face).apply();
            main_activity.updateForSettings(true, "");
        }
        if( mode == METER_CENTER || mode == METER_SPOT ) {
            CameraController cc = preview.getCameraController();
            if( cc != null ) {
                int r = mode == METER_CENTER ? 500 : 100;
                ArrayList<CameraController.Area> areas = new ArrayList<>();
                areas.add(new CameraController.Area(new Rect(-r, -r, r, r), 1000));
                cc.setFocusAndMeteringArea(areas);
            }
        }
        else {
            preview.clearFocusAreas();
        }
        meter_mode = mode;
        refresh();
    }

    private void cycleWhiteBalance() {
        if( locked )
            return;
        Preview preview = main_activity.getPreview();
        if( preview == null )
            return;
        List<String> supported = preview.getSupportedWhiteBalances();
        CameraController cc = preview.getCameraController();
        if( supported == null || cc == null )
            return;
        String current = main_activity.getApplicationInterface().getWhiteBalancePref();
        int index = -1;
        for(int i = 0; i < WB_VALUES.length; i++) {
            if( WB_VALUES[i].equals(current) )
                index = i;
        }
        for(int k = 1; k <= WB_VALUES.length; k++) {
            String candidate = WB_VALUES[(index + k + WB_VALUES.length) % WB_VALUES.length];
            if( supported.contains(candidate) ) {
                cc.setWhiteBalance(candidate);
                main_activity.getApplicationInterface().setWhiteBalancePref(candidate);
                break;
            }
        }
        refresh();
    }

    private void toggleWhiteBalanceLock() {
        if( locked )
            return;
        Preview preview = main_activity.getPreview();
        if( preview != null && preview.supportsWhiteBalanceLock() )
            preview.toggleWhiteBalanceLock();
        refresh();
    }

    private void toggleHDR() {
        if( locked )
            return;
        if( !main_activity.supportsHDR() ) {
            Toast.makeText(main_activity, "HDR not supported on this camera", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences sp = prefs();
        String mode = isHDR(sp) ? PHOTO_MODE_STD : PHOTO_MODE_HDR;
        sp.edit().putString(PreferenceKeys.PhotoModePreferenceKey, mode).apply();
        main_activity.updateForSettings(true, "");
        refresh();
    }
}
