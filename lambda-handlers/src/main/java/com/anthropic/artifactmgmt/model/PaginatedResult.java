package com.anthropic.artifactmgmt.model;

import java.util.List;

public final class PaginatedResult<T> {
  private final List<T> items;
  private final String nextPageToken;

  public PaginatedResult(List<T> items, String nextPageToken) {
    this.items = items;
    this.nextPageToken = nextPageToken;
  }

  public List<T> items() {
    return items;
  }

  public String nextPageToken() {
    return nextPageToken;
  }
}
