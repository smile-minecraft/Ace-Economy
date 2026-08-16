package com.smile.aceeconomy.ports;

import java.time.Instant;

/** Time source seam. Keeps the domain/application deterministic and testable. */
public interface Clock {

    Instant instant();
}
