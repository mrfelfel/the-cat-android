/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;

import com.google.android.gms.auth.api.phone.SmsRetriever;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        try {
            String message = "";
            SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
            String hash = preferences.getString("sms_hash", null);
            if (SmsRetriever.SMS_RETRIEVED_ACTION.equals(intent.getAction())) {
                if (!AndroidUtilities.isWaitingForSms()) {
                    return;
                }
                Bundle bundle = intent.getExtras();
                message = (String) bundle.get(SmsRetriever.EXTRA_SMS_MESSAGE);
            }
            if (TextUtils.isEmpty(message)) {
                return;
            }
            // prefer a code-shaped number so expiry minutes and footer digits
            // earlier in the text don't get autofilled instead of the login code
            String code = firstMatch(Pattern.compile("(?<![0-9])[0-9]{4,6}(?![0-9])"), message);
            if (code == null) {
                code = firstMatch(Pattern.compile("[0-9\\-]+"), message);
            }
            if (code != null) {
                code = code.replace("-", "");
                if (code.length() >= 3) {
                    if (hash != null) {
                        preferences.edit().putString("sms_hash_code", hash + "|" + code).commit();
                    }
                    final String finalCode = code;
                    AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didReceiveSmsCode, finalCode));
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static String firstMatch(Pattern pattern, String message) {
        Matcher matcher = pattern.matcher(message);
        return matcher.find() ? matcher.group(0) : null;
    }
}
