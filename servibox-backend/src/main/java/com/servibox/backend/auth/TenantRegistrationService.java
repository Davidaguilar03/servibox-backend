package com.servibox.backend.auth;

import com.servibox.backend.auth.dto.LoginResponse;
import com.servibox.backend.auth.dto.RegisterRequest;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.counterparties.service.CounterpartyService;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.entity.TaxType;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.shared.TenantAwareEntity;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.AccountType;
import com.servibox.backend.treasury.service.TreasuryService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Registro publico de una serviteca: valida, crea el tenant y su administrador y
 * aprovisiona lo minimo para operar, todo en una transaccion. Si algo falla no queda nada
 * del tenant. Devuelve la misma sesion que el login. Ver 03-DECISIONS.md.
 */
@Service
@RequiredArgsConstructor
public class TenantRegistrationService {

    /** Rutas de la API y del frontend, y nombres que no deberian ser de un cliente. */
    static final Set<String> SLUGS_RESERVADOS = Set.of(
            "api", "admin", "www", "app", "login", "registro", "register", "servibox", "soporte",
            "static", "assets", "health",
            "auth", "inventory", "treasury", "counterparties", "sales", "purchases", "reporting", "demo");

    /** Categorias base con su color de chip (los de la siembra dev para las que coinciden). */
    private static final Map<String, String> CATEGORIAS_BASE = new LinkedHashMap<>();

    static {
        CATEGORIAS_BASE.put("LLANTAS", "#e74c3c");
        CATEGORIAS_BASE.put("LUBRICANTES", "#f1c40f");
        CATEGORIAS_BASE.put("BATERIAS", "#2ecc71");
    }

    private static final Pattern SLUG = Pattern.compile("[a-z0-9-]{3,30}");
    private static final Pattern NIT = Pattern.compile("\\d+(-\\d)?");
    private static final Pattern TELEFONO = Pattern.compile("[0-9 +()-]+");
    private static final Pattern CORREO = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,50}");

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final InventoryService inventoryService;
    private final TreasuryService treasuryService;
    private final CounterpartyService counterpartyService;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${servibox.registration.enabled}")
    private boolean enabled;

    @Transactional
    public LoginResponse registrar(RegisterRequest request) {
        if (!enabled) {
            throw new RegistrationDisabledException();
        }
        validar(request);

        String slug = request.slug();
        if (tenantRepository.existsBySlugIgnoreCase(slug)) {
            throw new DuplicateTenantSlugException(slug);
        }

        Tenant tenant = new Tenant();
        tenant.setName(request.businessName().trim());
        tenant.setNit(request.nit().trim());
        tenant.setPhone(request.phone().trim());
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        try {
            tenant = tenantRepository.save(tenant);
        } catch (DataIntegrityViolationException ex) {
            // Dos registros simultaneos con el mismo slug pasan los dos la consulta de
            // arriba; el segundo choca aqui con la restriccion. Sin reintento: 409.
            if (String.valueOf(ex.getMostSpecificCause().getMessage()).toLowerCase()
                    .contains("uk_tenant_slug")) {
                throw new DuplicateTenantSlugException(slug);
            }
            throw ex;
        }

        // Igual que DevDataInitializer: TenantAwareEntity toma el tenant de TenantContext en
        // @PrePersist. La transaccion empezo sin tenant, asi que el filtro de Hibernate se
        // habilita aqui a mano, como haria TenantAwareJpaTransactionManager.
        TenantContext.setTenantId(tenant.getId());
        entityManager.unwrap(Session.class)
                .enableFilter(TenantAwareEntity.TENANT_FILTER)
                .setParameter(TenantAwareEntity.TENANT_PARAM, tenant.getId());
        try {
            User admin = new User();
            admin.setUsername(request.adminUsername());
            admin.setPasswordHash(passwordEncoder.encode(request.password()));
            admin.setEmail(request.adminEmail().trim());
            admin.setFullName(request.adminFullName().trim());
            admin.setActive(true);
            admin.setRole(Role.ADMIN);
            userRepository.save(admin);

            TaxType iva = inventoryService.crearIvaPorDefecto();
            CATEGORIAS_BASE.forEach((nombre, color) -> {
                ProductCategory categoria = new ProductCategory();
                categoria.setName(nombre);
                categoria.setColor(color);
                categoria.setYellowStockMin(10);
                categoria.setRedStockMin(3);
                categoria.setTargetMargin(0.0);
                categoria.setTaxTypes(Set.of(iva));
                inventoryService.saveCategory(categoria);
            });

            Account caja = new Account();
            caja.setName("Caja General");
            caja.setType(AccountType.EFECTIVO);
            caja.setInitialBalance(0.0);
            treasuryService.saveAccount(caja);

            counterpartyService.consumidorFinal();

            return LoginResponse.from(jwtService.generateToken(admin), admin, tenant);
        } finally {
            TenantContext.clear();
        }
    }

    private void validar(RegisterRequest r) {
        if (!Boolean.TRUE.equals(r.acceptTerms())) {
            throw new InvalidRegistrationException("Debe aceptar los terminos y condiciones");
        }

        validarNombreComercial(r.businessName());
        validarNit(r.nit());
        validarTelefono(r.phone());

        String slug = r.slug();
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new InvalidRegistrationException(
                    "El identificador debe tener de 3 a 30 caracteres con solo minusculas, numeros y guiones");
        }
        if (slug.startsWith("-") || slug.endsWith("-")) {
            throw new InvalidRegistrationException("El identificador no puede empezar ni terminar en guion");
        }
        if (SLUGS_RESERVADOS.contains(slug)) {
            throw new InvalidRegistrationException("El identificador " + slug + " esta reservado");
        }

        obligatorio(r.adminFullName(), 150, "El nombre completo del administrador");

        String correo = r.adminEmail() == null ? "" : r.adminEmail().trim();
        if (correo.length() > 150) {
            throw new InvalidRegistrationException("El correo del administrador no puede superar 150 caracteres");
        }
        if (!CORREO.matcher(correo).matches()) {
            throw new InvalidRegistrationException("El correo del administrador no es valido");
        }

        if (r.adminUsername() == null || !USERNAME.matcher(r.adminUsername()).matches()) {
            throw new InvalidRegistrationException(
                    "El usuario debe tener de 3 a 50 caracteres con letras, numeros, punto, guion y guion bajo");
        }

        String password = r.password();
        if (password == null || password.length() < 8) {
            throw new InvalidRegistrationException("La contrasena debe tener al menos 8 caracteres");
        }
        // BCrypt solo usa los primeros 72 bytes: con tildes o enies 72 caracteres pasan de 72 bytes.
        if (password.length() > 72 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InvalidRegistrationException("La contrasena no puede superar 72 caracteres");
        }
    }

    // Las reglas de los datos del tenant, tambien para la edicion del perfil (TenantService).

    public static void validarNombreComercial(String nombre) {
        obligatorio(nombre, 150, "El nombre comercial");
    }

    public static void validarNit(String nit) {
        obligatorio(nit, 25, "El NIT");
        if (!NIT.matcher(nit.trim()).matches()) {
            throw new InvalidRegistrationException(
                    "El NIT solo admite digitos y un guion con el digito de verificacion, por ejemplo 900123456-7");
        }
    }

    public static void validarTelefono(String telefono) {
        obligatorio(telefono, 25, "El telefono");
        if (!TELEFONO.matcher(telefono.trim()).matches()) {
            throw new InvalidRegistrationException("El telefono solo admite digitos, espacios, +, - y parentesis");
        }
    }

    private static void obligatorio(String valor, int maximo, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new InvalidRegistrationException(campo + " es obligatorio");
        }
        if (valor.trim().length() > maximo) {
            throw new InvalidRegistrationException(campo + " no puede superar " + maximo + " caracteres");
        }
    }
}
