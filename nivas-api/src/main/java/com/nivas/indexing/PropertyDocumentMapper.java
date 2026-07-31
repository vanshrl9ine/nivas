package com.nivas.indexing;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.elasticsearch.core.geo.GeoPoint;
import org.springframework.data.elasticsearch.core.suggest.Completion;
import org.springframework.stereotype.Component;

import com.nivas.catalog.domain.Amenity;
import com.nivas.catalog.domain.Locality;
import com.nivas.catalog.domain.Property;
import com.nivas.catalog.domain.PropertyImage;
import com.nivas.search.document.PropertyDocument;

/**
 * Projects the write-side aggregate into the flat search document.
 *
 * <p>This is where denormalisation happens on purpose: locality name, cover image
 * and price-per-sqft are all derivable, but computing them at query time would
 * mean either a join Elasticsearch cannot do or a script that runs per hit.
 */
@Component
public class PropertyDocumentMapper {

    public PropertyDocument toDocument(Property property) {
        PropertyDocument doc = new PropertyDocument();

        doc.setId(property.getId().toString());
        // Elasticsearch's external versioning treats 0 as "unversioned", so the
        // JPA version (which starts at 0) is shifted by one. Only the ordering
        // matters, and this keeps it strictly positive.
        doc.setVersion(property.getVersion() + 1);

        doc.setOwnerId(property.getOwner().getId().toString());
        doc.setTitle(property.getTitle());
        doc.setDescription(property.getDescription());

        doc.setListingType(property.getListingType().name());
        doc.setPropertyType(property.getPropertyType().name());
        doc.setStatus(property.getStatus().name());
        doc.setFurnishing(property.getFurnishing() == null ? null : property.getFurnishing().name());
        doc.setAmenities(property.getAmenities().stream().map(Amenity::name).sorted().toList());

        doc.setBedrooms(property.getBedrooms());
        doc.setBathrooms(property.getBathrooms());
        doc.setBalconies(property.getBalconies());
        doc.setFloorNumber(property.getFloorNumber());
        doc.setTotalFloors(property.getTotalFloors());
        doc.setAgeYears(property.getAgeYears());
        doc.setCarpetAreaSqft(property.getCarpetAreaSqft());
        doc.setBuiltUpAreaSqft(property.getBuiltUpAreaSqft());

        doc.setPrice(property.getPrice());
        doc.setPricePerSqft(property.pricePerSqft());
        doc.setMaintenanceMonthly(property.getMaintenanceMonthly());
        doc.setPriceNegotiable(property.isPriceNegotiable());

        Locality locality = property.getLocality();
        if (locality != null) {
            doc.setLocalityId(locality.getId().toString());
            doc.setLocalitySlug(locality.getSlug());
            doc.setLocalityName(locality.getName());
        }

        // The city facet and filter are lowercased at index time to match the
        // keyword normaliser in the mapping.
        doc.setCity(property.getCity() == null ? null : property.getCity().toLowerCase());
        doc.setState(property.getState() == null ? null : property.getState().toLowerCase());
        doc.setPincode(property.getPincode());
        doc.setAddressLine(property.getAddressLine());

        if (property.getLatitude() != null && property.getLongitude() != null) {
            doc.setLocation(new GeoPoint(property.getLatitude(), property.getLongitude()));
        }

        doc.setVerified(property.isVerified());
        doc.setViewCount(property.getViewCount());

        List<PropertyImage> images = property.getImages();
        doc.setImageCount(images.size());
        PropertyImage cover = property.coverImage();
        doc.setCoverImageUrl(cover == null ? null : cover.getUrl());

        doc.setAvailableFrom(property.getAvailableFrom());
        doc.setPublishedAt(property.getPublishedAt());
        doc.setUpdatedAt(property.getUpdatedAt());

        doc.setSuggest(buildSuggest(property));

        return doc;
    }

    /**
     * Feeds the type-ahead. Several phrasings are indexed for the same listing so
     * "2 bhk koramangala", "koramangala apartment" and the raw title all lead to
     * it — users do not type titles, they type intent.
     */
    private Completion buildSuggest(Property property) {
        List<String> inputs = new ArrayList<>(8);
        inputs.add(property.getTitle());

        Locality locality = property.getLocality();
        String area = locality != null ? locality.getName() : property.getCity();

        if (area != null) {
            /*
             * Area-leading phrasings matter more than they look.
             *
             * A completion suggester matches a prefix against the START of each
             * input, not anywhere inside it. Indexing only "Bright 2 BHK ... in
             * Koramangala" means someone typing "kora" — which is what people
             * actually type — matches nothing at all. So the area leads its own
             * inputs.
             */
            inputs.add(area);
            if (property.getBedrooms() != null) {
                inputs.add(area + " " + property.getBedrooms() + " BHK");
                inputs.add(property.getBedrooms() + " BHK in " + area);
            }
            inputs.add(area + " " + humanise(property.getPropertyType().name()).toLowerCase());
            inputs.add(humanise(property.getPropertyType().name()) + " in " + area);
        }

        if (property.getCity() != null && !property.getCity().equalsIgnoreCase(area)) {
            inputs.add(property.getCity());
        }

        Completion completion = new Completion(inputs.stream()
                .filter(input -> input != null && !input.isBlank())
                .distinct()
                .toList());

        // Verified listings surface first in the dropdown, mirroring the ranking
        // rule in the main query so suggestions do not contradict results.
        completion.setWeight(property.isVerified() ? 10 : 1);
        return completion;
    }

    private static String humanise(String value) {
        String spaced = value.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
