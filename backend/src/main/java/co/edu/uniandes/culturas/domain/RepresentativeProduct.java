package co.edu.uniandes.culturas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Producto de marca representativo de una categoría: «Aceite de oliva», Bertolli.
 */
@Entity
@Getter
@Setter
@ToString
@Table(
        name = "representative_product",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_product_name_brand",
                columnNames = {"name", "brand"}))
public class RepresentativeProduct extends BaseEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 120)
    private String brand;

    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private GastronomicCategory category;
}
