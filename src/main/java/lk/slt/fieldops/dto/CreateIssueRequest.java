package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateIssueRequest {

    private String title;

    @NotBlank(message = "Description is required")
    private String description;

    @NotBlank(message = "Category is required")
    private String category; // broadband | telephone | fiber | television | other

    private LocationDto location;

    private String[] photos;

    public static class LocationDto {
        private String address;
        private Double latitude;
        private Double longitude;

        public String getAddress()   { return address; }
        public Double getLatitude()  { return latitude; }
        public Double getLongitude() { return longitude; }

        public void setAddress(String v)   { this.address   = v; }
        public void setLatitude(Double v)  { this.latitude  = v; }
        public void setLongitude(Double v) { this.longitude = v; }
    }

    public CreateIssueRequest() {}

    public String      getTitle()       { return title; }
    public String      getDescription() { return description; }
    public String      getCategory()    { return category; }
    public LocationDto getLocation()    { return location; }
    public String[]    getPhotos()      { return photos; }

    public void setTitle(String v)       { this.title       = v; }
    public void setDescription(String v) { this.description = v; }
    public void setCategory(String v)    { this.category    = v; }
    public void setLocation(LocationDto v) { this.location  = v; }
    public void setPhotos(String[] v)    { this.photos      = v; }
}
