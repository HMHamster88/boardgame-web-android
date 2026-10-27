package com.hmhamster.boardgameweb;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Live stdout/stderr view for the Node.js core module, with a command input
 * wired to the module's stdin-based CLI.
 */
public class ServerConsoleActivity extends Activity {
    private static final int MAX_DISPLAY_CHARS = 200_000;

    private final NodeRuntime.OutputListener outputListener =
            (stream, text) -> runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    appendOutput(stream, text, true);
                }
            });

    private ScrollView scrollView;
    private TextView outputView;
    private EditText commandInput;
    private Button sendButton;
    private boolean listenerRegistered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_console);

        scrollView = findViewById(R.id.console_scroll);
        outputView = findViewById(R.id.console_output);
        commandInput = findViewById(R.id.command_input);
        sendButton = findViewById(R.id.send_button);

        outputView.setText("", TextView.BufferType.EDITABLE);

        ImageButton backButton = findViewById(R.id.console_back);
        backButton.setOnClickListener(view -> finish());

        Button clearButton = findViewById(R.id.clear_button);
        clearButton.setOnClickListener(view -> clearOutput());

        sendButton.setOnClickListener(view -> sendCommand());
        sendButton.setEnabled(false);

        commandInput.setOnEditorActionListener((view, actionId, event) -> {
            boolean enterPressed = event != null
                    && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;

            if (actionId == EditorInfo.IME_ACTION_SEND || enterPressed) {
                sendCommand();
                return true;
            }

            return false;
        });

        commandInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                sendButton.setEnabled(text.toString().trim().length() > 0);
            }

            @Override
            public void afterTextChanged(Editable text) {
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (listenerRegistered) {
            return;
        }

        List<NodeRuntime.OutputChunk> backlog =
                NodeRuntime.addOutputListenerAndSnapshot(outputListener);
        listenerRegistered = true;

        outputView.setText("", TextView.BufferType.EDITABLE);
        for (NodeRuntime.OutputChunk chunk : backlog) {
            appendOutput(chunk.stream, chunk.text, false);
        }

        if (NodeRuntime.hasExited()) {
            appendOutput(
                    NodeRuntime.STREAM_STDERR,
                    getString(R.string.console_node_exited, NodeRuntime.getExitCode()) + "\n",
                    false
            );
        }

        scrollToBottom(true);
    }

    @Override
    protected void onStop() {
        if (listenerRegistered) {
            NodeRuntime.removeOutputListener(outputListener);
            listenerRegistered = false;
        }

        super.onStop();
    }

    private void appendOutput(int stream, String text, boolean scroll) {
        if (text == null || text.isEmpty()) {
            return;
        }

        Editable editable = outputView.getEditableText();
        if (editable == null) {
            outputView.setText("", TextView.BufferType.EDITABLE);
            editable = outputView.getEditableText();
        }

        int start = editable.length();
        editable.append(text);

        int color = 0;
        if (stream == NodeRuntime.STREAM_STDERR) {
            color = getColor(R.color.console_error);
        } else if (stream == NodeRuntime.STREAM_COMMAND) {
            color = getColor(R.color.console_command);
        }

        if (color != 0) {
            editable.setSpan(
                    new ForegroundColorSpan(color),
                    start,
                    editable.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }

        if (editable.length() > MAX_DISPLAY_CHARS) {
            editable.delete(0, editable.length() - MAX_DISPLAY_CHARS);
        }

        if (scroll) {
            scrollToBottom(true);
        }
    }

    private void scrollToBottom(boolean force) {
        View child = scrollView.getChildAt(0);
        if (child == null) {
            return;
        }

        int distanceFromBottom = child.getBottom() - (scrollView.getHeight() + scrollView.getScrollY());
        if (force || distanceFromBottom < 160) {
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void sendCommand() {
        String command = commandInput.getText().toString().trim();
        if (command.isEmpty()) {
            return;
        }

        Editable editable = outputView.getEditableText();
        boolean needsNewline = editable != null
                && editable.length() > 0
                && editable.charAt(editable.length() - 1) != '\n';

        appendOutput(
                NodeRuntime.STREAM_COMMAND,
                (needsNewline ? "\n" : "") + "> " + command + "\n",
                true
        );

        if (!NodeRuntime.sendCommand(command)) {
            appendOutput(
                    NodeRuntime.STREAM_STDERR,
                    getString(R.string.server_not_running) + "\n",
                    true
            );
            return;
        }

        commandInput.setText("");
        commandInput.requestFocus();
    }

    private void clearOutput() {
        outputView.setText("", TextView.BufferType.EDITABLE);
        NodeRuntime.clearOutputBuffer();
    }
}
