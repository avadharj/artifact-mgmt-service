package com.anthropic.artifactmgmt.version;

import com.anthropic.artifactmgmt.exception.InvalidMajorVersionException;
import java.util.Optional;

public class VersionIncrementer {

  /**
   * Models are created with latestMajor=1, latestMinor=-1 so the first minor bump produces (1,0).
   */
  public IncrementResult next(
      int currentMajor, int currentMinor, Optional<Integer> requestedMajor) {
    if (requestedMajor.isEmpty() || requestedMajor.get() == currentMajor) {
      return IncrementResult.minorBump(currentMajor, currentMinor + 1, currentMajor);
    }
    if (requestedMajor.get() < currentMajor) {
      throw new InvalidMajorVersionException(requestedMajor.get(), currentMajor);
    }
    return IncrementResult.majorBump(requestedMajor.get(), 0, currentMajor);
  }
}
