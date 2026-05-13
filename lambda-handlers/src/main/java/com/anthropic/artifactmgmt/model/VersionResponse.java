package com.anthropic.artifactmgmt.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VersionResponse {
  String modelName;
  String version;
  VersionStatus status;
  String s3Key;
  String createdAt;
  String createdBy;

  // Optional — populated by Get/GetLatest (Story 5.1), null on Confirm response.
  JsonNode depSnapshot;
  JsonNode trainingMetadata;
  String downloadUrl;
  String downloadUrlExpiresAt;

  /** Used by ConfirmVersion — metadata only, no presigned download URL. */
  public static VersionResponse from(Version v) {
    return VersionResponse.builder()
        .modelName(v.getModelName())
        .version(v.getMajor() + "." + v.getMinor())
        .status(v.getStatus())
        .s3Key(v.getS3Key())
        .createdAt(v.getCreatedAt())
        .createdBy(v.getCreatedBy())
        .build();
  }

  /**
   * Used by Get/GetLatestVersion. The dep_snapshot and training_metadata columns are stored as JSON
   * strings; this re-parses them to JsonNode so the response carries structured JSON instead of an
   * escaped string. Null download URL is allowed (GetVersion on a non-READY row).
   */
  public static VersionResponse fromWithDownload(
      Version v, ObjectMapper mapper, String downloadUrl, String downloadUrlExpiresAt) {
    return VersionResponse.builder()
        .modelName(v.getModelName())
        .version(v.getMajor() + "." + v.getMinor())
        .status(v.getStatus())
        .s3Key(v.getS3Key())
        .createdAt(v.getCreatedAt())
        .createdBy(v.getCreatedBy())
        .depSnapshot(parseJson(mapper, v.getDepSnapshot()))
        .trainingMetadata(parseJson(mapper, v.getTrainingMetadata()))
        .downloadUrl(downloadUrl)
        .downloadUrlExpiresAt(downloadUrlExpiresAt)
        .build();
  }

  private static JsonNode parseJson(ObjectMapper mapper, String json) {
    if (json == null) return null;
    try {
      return mapper.readTree(json);
    } catch (Exception e) {
      return null;
    }
  }
}
