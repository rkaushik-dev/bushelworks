package io.github.rkaushikdev.bushelworks.services.board;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a bid cannot be posted or a day sheet entry does not make sense. */
@ErrorCode("board_error")
public class BoardError extends ValidationError {

    public BoardError(String message) {
        super(message);
    }
}
