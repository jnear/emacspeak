/*
 * Copyright (C) 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.emacspeak.android;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;

/**
 * LocalTtsServerService is a background service that implements a local speech
 * server on Android which follows the emacspeak speech server specs as
 * documented here:
 * http://emacspeak.sourceforge.net/info/TTS-Servers.html#TTS-Servers
 *
 * @author clchen@google.com (Charles Chen)
 */
public class LocalTtsServerService extends Service {
  private class Utterance {
    public static final int TYPE_SPEECH = 0;
    public static final int TYPE_SOUND = 1;
    public static final int TYPE_SILENCE = 2;
    public static final int TYPE_TONE = 3;
    public int utteranceType;
    public String payload;

    public Utterance(int utteranceType, String payload) {
      this.utteranceType = utteranceType;
      this.payload = payload;
    }
  }

  private static final int PORT = 8383;
  private static final float SPEECH_RATE = 4;

  private ServerSocket mServerSocket;
  private Socket mSocket;
  private InputStream mInputStream;
  private TextToSpeech mTts;
  private ArrayList<Utterance> mQueue;

  /*
   * (non-Javadoc)
   *
   * @see android.app.Service#onBind(android.content.Intent)
   */
  @Override
  public IBinder onBind(Intent arg0) {
    // TODO(clchen): Auto-generated method stub
    return null;
  }

  @Override
  public void onStart(Intent intent, int startId) {
    super.onStart(intent, startId);
    // Raise the TTS priority level so that it is less likely to randomly get
    // bumped by the system.
    // tvr:shouldn't need this any more?
    this.startForeground(0, null);
    mQueue = new ArrayList<Utterance>();
    mTts = new TextToSpeech(this, new OnInitListener() {
      @Override
      public void onInit(int arg0) {
        mTts.setSpeechRate(SPEECH_RATE);
        mTts.speak("Speech server ready.", 0, null);
      }
    });
    Log.e("server", "starting server");
    new Thread(incomingDataProcessor).start();
  }


  private Runnable incomingDataProcessor = new Runnable() {
    @Override
    public void run() {
      try {
        mServerSocket = new ServerSocket(PORT);
        Log.e("server", "server socket created");
        mSocket = mServerSocket.accept();
        Log.e("server", "server connected");
        mInputStream = mSocket.getInputStream();
        LineNumberReader lr = new LineNumberReader(new InputStreamReader(mInputStream));
        while (true) {
          processData(lr.readLine());
        }
      } catch (IOException e) {
        // TODO(clchen): Auto-generated catch block
        e.printStackTrace();
      }
    }
  };

  private void processData(String command) {
    try {
      Log.d("server", "command: " + command);
      command = command.replaceAll("\\{", "").replaceAll("\\}", "");

      if (command.startsWith("version")) {
        PackageInfo pinfo = getPackageManager().getPackageInfo(getPackageName(), 0);
        mTts.speak(pinfo.versionName, 2, null);
      } else if (command.startsWith("tts_say ")) {
        command = command.replaceFirst("tts_say ", "");
        mTts.speak(command, 2, null);
      } else if (command.startsWith("l ")) {
        command = command.replaceFirst("l ", "");
        mTts.speak(command, 2, null);
      } else if (command.startsWith("s")) {
        mTts.speak("", 2, null);
        mQueue = new ArrayList<Utterance>();
      }else if (command.startsWith("c ")) {
        command = command.replaceFirst("c ", "");
        mQueue.add(new Utterance(Utterance.TYPE_SPEECH, command));
      } else if (command.startsWith("q ")) {
        command = command.replaceFirst("q ", "");
        mQueue.add(new Utterance(Utterance.TYPE_SPEECH, command));
      } else if (command.startsWith("d")) {
        // TODO: Account for queued audio and silences
        // tvr:We should  queue to the TTSlayer, not
        // concatenate which will GC

        // then start queuing from the mQueue
        String message = "";
        while (mQueue.size() > 0) {
          // check if the next chunk will go over the maximum speech input length
          //int maxLength = mTts.getMaxSpeechInputLength();
          int maxLength = 1000;
          if (message.length() + mQueue.get(0).payload.length() >= maxLength) {
            // if so, queue up the previous chunks and reset
            mTts.speak(message, 1, null);
            message = "";
          }
          message = message + mQueue.remove(0).payload + "\n";
        }

        // speak the last set of chunks
        mTts.speak(message, 1, null);
      }
    } catch (PackageManager.NameNotFoundException e) {
      e.printStackTrace();
    }
  }
}
