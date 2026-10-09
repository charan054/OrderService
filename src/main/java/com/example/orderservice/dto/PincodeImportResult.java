package com.example.orderservice.dto;

import java.util.List;

// Outcome of POST /pincodes/import: how many rows were saved and, for each rejected row, why.
public record PincodeImportResult(int imported, List<String> errors) {
}
