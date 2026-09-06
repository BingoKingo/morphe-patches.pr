/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.content.Intent;
import android.app.Dialog;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.net.Uri;
import android.preference.Preference;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * Token entry for the Musixmatch source. Mirrors {@link AppleMusicTokenPreference}.
 */
@SuppressWarnings({"unused", "deprecation"})
public class MusixmatchTokenPreference extends Preference {

    public MusixmatchTokenPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public MusixmatchTokenPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public MusixmatchTokenPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MusixmatchTokenPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setSelectable(true);
        setPersistent(false);
        updateSummary();
    }

    private void updateSummary() {
        setSummary(str(!Settings.MUSIXMATCH_TOKEN.get().isBlank()
                ? "morphe_music_musixmatch_token_summary_set"
                : "morphe_music_musixmatch_token_summary"));
    }

    @Override
    protected void onClick() {
        showDialog();
    }

    private void showDialog() {
        Context context = getContext();
        final boolean configured = !Settings.MUSIXMATCH_TOKEN.get().isBlank();

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView instruction = new TextView(context);
        instruction.setText(str("morphe_music_musixmatch_token_dialog_instruction"));
        instruction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        instruction.setTextColor(ThemeUtils.getAppForegroundColor());
        LinearLayout.LayoutParams instructionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        instructionParams.bottomMargin = Dim.dp12;
        content.addView(instruction, instructionParams);

        EditText tokenInput = createThemedEditText(context);
        tokenInput.setHint(str("morphe_music_musixmatch_token_dialog_hint"));
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (configured) {
            String currentToken = Settings.MUSIXMATCH_TOKEN.get();
            tokenInput.setText(currentToken);
            tokenInput.setSelection(currentToken.length());
        }
        content.addView(tokenInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTextColor(ThemeUtils.getAppForegroundColor());
        status.setVisibility(View.GONE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = Dim.dp12;

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_music_musixmatch_token_title"),
                null,
                null,
                str("morphe_settings_save"),
                () -> {
                    String token = tokenInput.getText().toString().trim();
                    if (token.isEmpty()) {
                        Settings.MUSIXMATCH_TOKEN.resetToDefault();
                        updateSummary();
                        Utils.showToastShort(str("morphe_music_musixmatch_token_toast_cleared"));
                    } else {
                        Settings.MUSIXMATCH_TOKEN.save(token);
                        updateSummary();
                        Utils.showToastShort(str("morphe_music_musixmatch_token_toast_saved"));
                    }
                },
                null,
                str("morphe_music_scrobbling_log_out"),
                configured ? () -> {
                    Settings.MUSIXMATCH_TOKEN.resetToDefault();
                    updateSummary();
                    Utils.showToastShort(str("morphe_music_musixmatch_token_toast_cleared"));
                } : null,
                true
        );

        Dialog dialog = dialogPair.first;
        LinearLayout mainLayout = dialogPair.second;

        Button getTokenBtn = CustomDialog.createButton(context, null,
                str("morphe_music_musixmatch_token_dialog_get_token"),
                () -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://www.musixmatch.com"));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    } catch (Exception ex) {
                        Logger.printException(() -> "MusixmatchTokenPreference failed to open browser", ex);
                    }
                },
                false, false);

        LinearLayout.LayoutParams getTokenParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp36);
        getTokenParams.topMargin = Dim.dp12;
        content.addView(getTokenBtn, getTokenParams);

        content.addView(status, statusParams);

        // CustomDialog layout order: [title, buttonContainer]. Insert custom content before the buttons.
        mainLayout.addView(content, mainLayout.getChildCount() - 1,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        dialog.show();
    }

    private static EditText createThemedEditText(Context context) {
        EditText editText = new EditText(context);
        editText.setSingleLine(true);
        editText.setTextSize(16);
        editText.setTextColor(ThemeUtils.getAppForegroundColor());
        ShapeDrawable background = new ShapeDrawable(new RoundRectShape(
                Dim.roundedCorners(10), null, null));
        background.getPaint().setColor(ThemeUtils.getEditTextBackground());
        editText.setPadding(Dim.dp12, Dim.dp8, Dim.dp12, Dim.dp8);
        editText.setBackground(background);
        editText.setClipToOutline(true);
        return editText;
    }
}
