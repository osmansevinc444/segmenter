package com.streameast.segmenter.util;

import java.time.format.DateTimeFormatter;

public final class AppConstants {

    public static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    public static final int SEGMENT_PROCESSING_DELAY_MS = 500;


}
