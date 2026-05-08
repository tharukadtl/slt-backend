package lk.slt.fieldops.dto;

public class TechnicianCheckoutStatusDTO {

    private Long   technicianId;
    private String technicianName;
    private boolean checkedIn;
    private boolean checkedOut;
    private String  checkInTime;
    private String  checkOutTime;

    public TechnicianCheckoutStatusDTO() {}

    public TechnicianCheckoutStatusDTO(Long technicianId, String technicianName,
                                       boolean checkedIn, boolean checkedOut,
                                       String checkInTime, String checkOutTime) {
        this.technicianId   = technicianId;
        this.technicianName = technicianName;
        this.checkedIn      = checkedIn;
        this.checkedOut     = checkedOut;
        this.checkInTime    = checkInTime;
        this.checkOutTime   = checkOutTime;
    }

    public Long    getTechnicianId()   { return technicianId; }
    public String  getTechnicianName() { return technicianName; }
    public boolean isCheckedIn()       { return checkedIn; }
    public boolean isCheckedOut()      { return checkedOut; }
    public String  getCheckInTime()    { return checkInTime; }
    public String  getCheckOutTime()   { return checkOutTime; }
}
