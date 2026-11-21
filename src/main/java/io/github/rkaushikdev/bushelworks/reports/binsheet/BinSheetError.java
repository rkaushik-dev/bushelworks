package io.github.rkaushikdev.bushelworks.reports.binsheet;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a bin sheet cannot be built. */
@ErrorCode("bin_sheet_error")
public class BinSheetError extends ValidationError {

    public BinSheetError(String message) {
        super(message);
    }
}
