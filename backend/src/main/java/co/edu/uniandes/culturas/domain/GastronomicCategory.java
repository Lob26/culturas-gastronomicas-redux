package co.edu.uniandes.culturas.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * Agrupación de productos dentro de una cultura: entradas, postres, bebidas.
 *
 * <p>El nombre es único <em>por cultura</em>, no globalmente: «Postres» existe a
 * la vez en la cocina italiana y en la japonesa. El servicio de 2023 buscaba por
 * nombre global, así que crear «Postres» en la italiana devolvía silenciosamente
 * la categoría de la japonesa.
 */
@Entity
@Getter
@Setter
@ToString
@Table(
        name = "gastronomic_category",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_category_name_culture",
                columnNames = {"name", "culture_id"}))
public class GastronomicCategory extends BaseEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    /**
     * Sin cascada hacia el padre. En 2023 esto era
     * {@code @ManyToOne(cascade = CascadeType.REMOVE)}, de modo que borrar UNA
     * categoría intentaba borrar la cultura entera y, por las cascadas de ésta,
     * todas sus recetas y demás categorías. La cascada va hacia abajo, nunca
     * hacia arriba.
     */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "culture_id", nullable = false)
    private GastronomicCulture culture;

    @ToString.Exclude
    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<RepresentativeProduct> products = new ArrayList<>();

    public void addProduct(RepresentativeProduct product) {
        products.add(product);
        product.setCategory(this);
    }

    public void removeProduct(RepresentativeProduct product) {
        products.remove(product);
        product.setCategory(null);
    }
}
