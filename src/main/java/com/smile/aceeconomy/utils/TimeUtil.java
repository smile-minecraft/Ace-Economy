package com.smile.aceeconomy.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimeUtil {

    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)([dhms])");

    /**
     * 解析時間持續字串 (例如 "1h", "30m", "1d") 為毫秒數。
     *
     * @param durationStr 持續時間字串
     * @return 毫秒數，若格式錯誤回傳 -1
     */
    public static long parseDuration(String durationStr) {
        Matcher matcher = DURATION_PATTERN.matcher(durationStr.toLowerCase());
        long totalMillis = 0;
        boolean found = false;

        while (matcher.find()) {
            found = true;
            int value = Integer.parseInt(matcher.group(1));
            String unit = matcher.group(2);

            switch (unit) {
                case "d" -> totalMillis += value * 24L * 60 * 60 * 1000;
                case "h" -> totalMillis += value * 60L * 60 * 1000;
                case "m" -> totalMillis += value * 60L * 1000;
                case "s" -> totalMillis += value * 1000L;
            }
        }

        return found ? totalMillis : -1;
    }
}
