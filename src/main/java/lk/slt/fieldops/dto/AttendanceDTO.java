package lk.slt.fieldops.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class AttendanceDTO {

    // ─── Check-In Request ─────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CheckInRequest {

        @NotNull(message = "Latitude is required")
        @DecimalMin(value = "-90.0",
                message = "Latitude must be >= -90")
        @DecimalMax(value = "90.0",
                message = "Latitude must be <= 90")
        private Double latitude;

        @NotNull(message = "Longitude is required")
        @DecimalMin(value = "-180.0",
                message = "Longitude must be >= -180")
        @DecimalMax(value = "180.0",
                message = "Longitude must be <= 180")
        private Double longitude;

        private String address;
        private String deviceInfo;
        private String notes;
    }

    // ─── Check-Out Request ────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CheckOutRequest {

        @NotNull(message = "Latitude is required")
        @DecimalMin(value = "-90.0",
                message = "Latitude must be >= -90")
        @DecimalMax(value = "90.0",
                message = "Latitude must be <= 90")
        private Double latitude;

        @NotNull(message = "Longitude is required")
        @DecimalMin(value = "-180.0",
                message = "Longitude must be >= -180")
        @DecimalMax(value = "180.0",
                message = "Longitude must be <= 180")
        private Double longitude;

        private String address;
        private String notes;
        private Integer jobsCompleted;
    }

    // ─── Attendance Response ──────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttendanceResponse {
        private Long id;
        private Long userId;
        private String userName;
        private String userRole;
        private String userPhone;

        // Check-in details
        private LocalDateTime checkInTime;
        private Double checkInLatitude;
        private Double checkInLongitude;
        private String checkInAddress;

        // Check-out details
        private LocalDateTime checkOutTime;
        private Double checkOutLatitude;
        private Double checkOutLongitude;
        private String checkOutAddress;

        // Session info
        private String status;
        private Long workingDurationMinutes;
        private String workingDurationFormatted;
        private Integer jobsCompleted;
        private String notes;
        private String date;
        private LocalDateTime createdAt;
    }

    // ─── Today Summary ────────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TodaySummaryDTO {
        private Long userId;
        private String userName;
        private boolean isCheckedIn;
        private LocalDateTime checkInTime;
        private String checkInAddress;
        private LocalDateTime checkOutTime;
        private String checkOutAddress;
        private String currentStatus;
        private Long elapsedMinutes;
        private String elapsedFormatted;
        private Integer jobsCompleted;
        private String date;
    }

    // ─── Team Attendance ──────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TeamAttendanceDTO {
        private Long teamId;
        private String teamName;
        private String date;
        private int totalMembers;
        private int checkedIn;
        private int checkedOut;
        private int absent;
        private double attendanceRate;
        private List<MemberAttendanceDTO> members;
    }

    // ─── Member Attendance ────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemberAttendanceDTO {
        private Long userId;
        private String userName;
        private String userRole;
        private String phone;
        private String avatarInitial;
        private boolean isCheckedIn;
        private boolean isCheckedOut;
        private LocalDateTime checkInTime;
        private LocalDateTime checkOutTime;
        private Long workingDurationMinutes;
        private String workingDurationFormatted;
        private Integer jobsCompleted;
        private String status;
    }

    // ─── Attendance History Summary ───────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttendanceHistorySummaryDTO {
        private Long userId;
        private String userName;
        private int totalDays;
        private int presentDays;
        private int absentDays;
        private double attendanceRate;
        private double avgWorkingHours;
        private int totalJobsCompleted;
        private List<AttendanceResponse> records;
    }
}