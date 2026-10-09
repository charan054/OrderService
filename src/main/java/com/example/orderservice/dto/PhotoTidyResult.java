package com.example.orderservice.dto;

import java.util.List;

// Outcome of POST /cart/reviews/photo/tidy. dryRun=true deleted nothing: "orphaned" files are what WOULD go. Otherwise
// they were deleted. tooRecent files are newer than the safety window (an upload not yet attached to a review) and are
// never touched. files names the orphaned photos.
public record PhotoTidyResult(boolean dryRun, int total, int inUse, int tooRecent, int orphaned, long orphanedBytes,
                              List<String> files) {
}
