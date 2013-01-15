/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2007 Kenny Root, Jeffrey Sharkey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.woltage.irssiconnectbot;

import java.util.List;

import org.woltage.irssiconnectbot.bean.SelectionArea;
import org.woltage.irssiconnectbot.service.FontSizeChangedListener;
import org.woltage.irssiconnectbot.service.TerminalBridge;
import org.woltage.irssiconnectbot.service.TerminalKeyListener;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelXorXfermode;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;
import de.mud.terminal.VDUBuffer;

/**
 * User interface {@link View} for showing a TerminalBridge in an
 * {@link Activity}. Handles drawing bitmap updates and passing keystrokes down
 * to terminal.
 *
 * @author jsharkey
 */
public class TerminalView extends View implements FontSizeChangedListener {

	private final Context context;
	public final TerminalBridge bridge;
	private final Paint paint;
	private final Paint cursorPaint;
	private final Paint cursorStrokePaint;

	// Cursor paints to distinguish modes
	private Path ctrlCursor, altCursor, shiftCursor;
	private RectF tempSrc, tempDst;
	private Matrix scaleMatrix;
	private static final Matrix.ScaleToFit scaleType = Matrix.ScaleToFit.FILL;

	private Toast notification = null;
	private String lastNotification = null;
	private volatile boolean notifications = true;

	// Related to Accessibility Features
	private boolean accessibilityActive = false;
	private StringBuffer accessibilityBuffer = null;
	private AccessibilityEventSender eventSender = null;
	private int ACCESSIBILITY_EVENT_THRESHOLD = 1000;
	private static final String SCREENREADER_INTENT_ACTION = "android.accessibilityservice.AccessibilityService";
	private static final String SCREENREADER_INTENT_CATEGORY = "android.accessibilityservice.category.FEEDBACK_SPOKEN";

	public TerminalView(Context context, TerminalBridge bridge) {
		super(context);

		this.context = context;
		this.bridge = bridge;
		paint = new Paint();

		setLayoutParams(new LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT));
		setFocusable(true);
		setFocusableInTouchMode(true);

		cursorPaint = new Paint();
		cursorPaint.setColor(bridge.color[bridge.defaultFg]);
		cursorPaint.setXfermode(new PixelXorXfermode(bridge.color[bridge.defaultBg]));
		cursorPaint.setAntiAlias(true);

		cursorStrokePaint = new Paint(cursorPaint);
		cursorStrokePaint.setStrokeWidth(0.1f);
		cursorStrokePaint.setStyle(Paint.Style.STROKE);

		/*
		 * Set up our cursor indicators on a 1x1 Path object which we can later
		 * transform to our character width and height
		 */
		// TODO make this into a resource somehow
		altCursor = new Path();
		altCursor.lineTo(0.5f, 0.33f);
		altCursor.lineTo(1.0f, 0.0f);

		shiftCursor = new Path();
		shiftCursor.moveTo(0.0f, 1.0f);
		shiftCursor.lineTo(0.5f, 0.66f);
		shiftCursor.lineTo(1.0f, 1.0f);

		ctrlCursor = new Path();
		ctrlCursor.moveTo(0.0f, 0.25f);
		ctrlCursor.lineTo(1.0f, 0.5f);
		ctrlCursor.lineTo(0.0f, 0.75f);

		// For creating the transform when the terminal resizes
		tempSrc = new RectF();
		tempSrc.set(0.0f, 0.0f, 1.0f, 1.0f);
		tempDst = new RectF();
		scaleMatrix = new Matrix();

		bridge.addFontSizeChangedListener(this);

		// connect our view up to the bridge
		setOnKeyListener(bridge.getKeyHandler());

		// Enable accessibility features if a screen reader is active.
		accessibilityActive = isScreenReaderActive();
	}

	public void destroy() {
		// tell bridge to destroy its bitmap
		bridge.parentDestroyed();
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);

		bridge.parentChanged(this);

		scaleCursors();
	}

	public void onFontSizeChanged(float size) {
		scaleCursors();
	}

	private void scaleCursors() {
		// Create a scale matrix to scale our 1x1 representation of the cursor
		tempDst.set(0.0f, 0.0f, bridge.charWidth, bridge.charHeight);
		scaleMatrix.setRectToRect(tempSrc, tempDst, scaleType);
	}

	@Override
	public void onDraw(Canvas canvas) {
		if(bridge.bitmap != null) {
			// draw the bitmap
			bridge.onDraw();

			// draw the bridge bitmap if it exists
			canvas.drawBitmap(bridge.bitmap, 0, 0, paint);

			// also draw cursor if visible
			if (bridge.buffer.isCursorVisible()) {
				int cursorColumn = bridge.buffer.getCursorColumn();
				final int cursorRow = bridge.buffer.getCursorRow();

				final int columns = bridge.buffer.getColumns();

				if (cursorColumn == columns)
					cursorColumn = columns - 1;

				if (cursorColumn < 0 || cursorRow < 0)
					return;

				int currentAttribute = bridge.buffer.getAttributes(
						cursorColumn, cursorRow);
				boolean onWideCharacter = (currentAttribute & VDUBuffer.FULLWIDTH) != 0;

				int x = cursorColumn * bridge.charWidth;
				int y = (bridge.buffer.getCursorRow()
						+ bridge.buffer.screenBase - bridge.buffer.windowBase)
						* bridge.charHeight;

				// Save the current clip and translation
				canvas.save();

				canvas.translate(x, y);
				canvas.clipRect(0, 0,
						bridge.charWidth * (onWideCharacter ? 2 : 1),
						bridge.charHeight);
				canvas.drawPaint(cursorPaint);

				final int deadKey = bridge.getKeyHandler().getDeadKey();
				if (deadKey != 0) {
					canvas.drawText(new char[] { (char)deadKey }, 0, 1, 0, 0, cursorStrokePaint);
				}

				// Make sure we scale our decorations to the correct size.
				canvas.concat(scaleMatrix);

				int metaState = bridge.getKeyHandler().getMetaState();

				if ((metaState & TerminalKeyListener.META_SHIFT_ON) != 0)
					canvas.drawPath(shiftCursor, cursorStrokePaint);
				else if ((metaState & TerminalKeyListener.META_SHIFT_LOCK) != 0)
					canvas.drawPath(shiftCursor, cursorPaint);

				if ((metaState & TerminalKeyListener.META_ALT_ON) != 0)
					canvas.drawPath(altCursor, cursorStrokePaint);
				else if ((metaState & TerminalKeyListener.META_ALT_LOCK) != 0)
					canvas.drawPath(altCursor, cursorPaint);

				if ((metaState & TerminalKeyListener.META_CTRL_ON) != 0)
					canvas.drawPath(ctrlCursor, cursorStrokePaint);
				else if ((metaState & TerminalKeyListener.META_CTRL_LOCK) != 0)
					canvas.drawPath(ctrlCursor, cursorPaint);

				// Restore previous clip region
				canvas.restore();
			}

			// draw any highlighted area
			if (bridge.isSelectingForCopy()) {
				SelectionArea area = bridge.getSelectionArea();
				canvas.save(Canvas.CLIP_SAVE_FLAG);
				canvas.clipRect(
					area.getLeft() * bridge.charWidth,
					area.getTop() * bridge.charHeight,
					(area.getRight() + 1) * bridge.charWidth,
					(area.getBottom() + 1) * bridge.charHeight
				);
				canvas.drawPaint(cursorPaint);
				canvas.restore();
			}
		}
	}

	public void notifyUser(String message) {
		if (!notifications)
			return;

		if (notification != null) {
			// Don't keep telling the user the same thing.
			if (lastNotification != null && lastNotification.equals(message))
				return;

			notification.setText(message);
			notification.show();
		} else {
			notification = Toast.makeText(context, message, Toast.LENGTH_SHORT);
			notification.show();
		}

		lastNotification = message;
	}

	/**
	 * Ask the {@link TerminalBridge} we're connected to to resize to a specific size.
	 * @param width
	 * @param height
	 */
	public void forceSize(int width, int height) {
		bridge.resizeComputed(width, height, getWidth(), getHeight());
	}

	/**
	 * Sets the ability for the TerminalView to display Toast notifications to the user.
	 * @param value whether to enable notifications or not
	 */
	public void setNotifications(boolean value) {
		notifications = value;
	}

	@Override
	public boolean onCheckIsTextEditor() {
		return true;
	}

	@Override
	public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
		outAttrs.imeOptions |=
			EditorInfo.IME_FLAG_NO_EXTRACT_UI |
			EditorInfo.IME_FLAG_NO_ENTER_ACTION |
			EditorInfo.IME_ACTION_NONE;
		outAttrs.inputType = EditorInfo.TYPE_NULL;
		return new BaseInputConnection(this, false) {
			@Override
			public boolean deleteSurroundingText (int leftLength, int rightLength) {
				if (rightLength == 0 && leftLength == 0) {
					return this.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
				}
				for (int i = 0; i < leftLength; i++) {
					this.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
				}
				// TODO: forward delete
				return true;
			}
		};
	}

	private boolean isScreenReaderActive() {
		// Restrict the set of intents to only accessibility services that have
		// the category FEEDBACK_SPOKEN (aka, screen readers).
		Intent screenReaderIntent = new Intent(SCREENREADER_INTENT_ACTION);
		screenReaderIntent.addCategory(SCREENREADER_INTENT_CATEGORY);
		List<ResolveInfo> screenReaders = context.getPackageManager().queryIntentServices(
				screenReaderIntent, 0);
		ContentResolver cr = context.getContentResolver();
		Cursor cursor = null;
		int status = 0;
		for (ResolveInfo screenReader : screenReaders) {
			// All screen readers are expected to implement a content provider
			// that responds to:
			// content://<nameofpackage>.providers.StatusProvider
			cursor = cr.query(Uri.parse("content://" + screenReader.serviceInfo.packageName
					+ ".providers.StatusProvider"), null, null, null, null);
			if (cursor != null) {
				cursor.moveToFirst();
				// These content providers use a special cursor that only has
				// one element, an integer that is 1 if the screen reader is
				// running.
				status = cursor.getInt(0);
				cursor.close();
				if (status == 1) {
					return true;
				}
			}
		}
		return false;
	}

	public StringBuffer getAccessibilityBuffer() {
		return accessibilityBuffer;
	}

	public void propagateConsoleText(char[] rawText, int length) {
		if (accessibilityActive) {
			if (accessibilityBuffer == null) {
				accessibilityBuffer = new StringBuffer();
			}

			for (int i = 0; i < length; ++i) {
				accessibilityBuffer.append(rawText[i]);
			}

			if (eventSender != null) {
				removeCallbacks(eventSender);
			} else {
				eventSender = new AccessibilityEventSender();
			}
			postDelayed(eventSender, ACCESSIBILITY_EVENT_THRESHOLD);
		}
	}

	private class AccessibilityEventSender implements Runnable {
		public void run() {
			synchronized (accessibilityBuffer) {
				// Strip console codes with regex matching control codes
				String regex = "" + ((char) 27) + (char) 92 + ((char) 91) + "[^m]+[m|:]";
				accessibilityBuffer = new StringBuffer(
						accessibilityBuffer.toString().replaceAll(regex, " "));

				// Apply Backspaces using backspace character sequence
				String backspaceCode = "" + ((char) 8) + ((char) 27) + ((char) 91) + ((char) 75);
				int i = accessibilityBuffer.indexOf(backspaceCode);
				while (i != -1) {
					if (i == 0) {
						accessibilityBuffer = accessibilityBuffer.replace(
								i, i + backspaceCode.length(), "");
					} else {
						accessibilityBuffer = accessibilityBuffer.replace(
								i - 1, i + backspaceCode.length(), "");
					}
					i = accessibilityBuffer.indexOf(backspaceCode);
				}

				if (accessibilityBuffer.length() > 0) {
					AccessibilityEvent event = AccessibilityEvent.obtain(
							AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);
					event.setFromIndex(0);
					event.setAddedCount(accessibilityBuffer.length());
					event.getText().add(accessibilityBuffer);

					sendAccessibilityEventUnchecked(event);
					accessibilityBuffer.setLength(0);
				}
			}
		}
	}
}
