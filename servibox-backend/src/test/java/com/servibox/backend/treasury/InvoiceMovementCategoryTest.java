package com.servibox.backend.treasury;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.service.SalesService;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.entity.MovementCategory;
import com.servibox.backend.treasury.entity.MovementType;
import com.servibox.backend.treasury.service.InvalidMovementOperationException;
import com.servibox.backend.treasury.service.TreasuryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Los movimientos que generan ventas y compras llevan su categoria (RECAUDO_VENTA,
 * PAGO_COMPRA) y no se pueden editar ni eliminar a mano: se gestionan desde la factura.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvoiceMovementCategoryTest {

    private static final String PASSWORD = "clave-correcta";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private SalesService salesService;

    @Autowired
    private PurchasesService purchasesService;

    @Autowired
    private TreasuryService treasuryService;

    private Product llanta;
    private Account caja;
    private Counterparty cliente;
    private Counterparty proveedor;

    private Long tenantId;

    @BeforeEach
    void seed() {
        tenantId = fixture.crearTenant();
        fixture.usarTenant(tenantId);
        llanta = fixture.crearProducto(fixture.crearCategoriaConIva(), "LLA-001", 100000.0, 10);
        caja = fixture.crearCuenta("Caja General");
        treasuryService.registrarIngresoOcasional(caja, "Fondeo de prueba", 1000000.0, null, null);
        cliente = fixture.crearCliente("Cliente Uno", "900123456");
        proveedor = fixture.crearProveedor("Importadora Andina", "900987654");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private Sale vender(String numero, com.servibox.backend.sales.entity.PaymentType tipo, Account cuenta) {
        return salesService.crearFactura(numero, cliente, LocalDate.now(), null, tipo, cuenta, "Efectivo",
                List.of(new SalesService.LineaFactura(llanta.getId(), 1, 100000.0)));
    }

    private Purchase comprar(String numero, com.servibox.backend.purchases.entity.PaymentType tipo, Account cuenta) {
        return purchasesService.crearFactura(numero, proveedor, LocalDate.now(), null, tipo, cuenta, "Efectivo",
                List.of(new PurchasesService.LineaCompra(llanta.getId(), 1, 100000.0)));
    }

    private List<Movement> deCategoria(MovementCategory categoria) {
        return treasuryService.buscarMovimientos(null, categoria, null, null);
    }

    private double saldoDe(Account cuenta) {
        return treasuryService.findAccountById(cuenta.getId()).orElseThrow().getCurrentBalance();
    }

    @Test
    void lasVentasYSusAbonosGeneranMovimientosRecaudoVenta() {
        Sale contado = vender("VEN-00001", com.servibox.backend.sales.entity.PaymentType.CONTADO, caja);
        Sale credito = vender("VEN-00002", com.servibox.backend.sales.entity.PaymentType.CREDITO, null);
        salesService.registrarAbono(credito.getId(), caja.getId(), 19000.0);

        assertThat(deCategoria(MovementCategory.RECAUDO_VENTA))
                .hasSize(2)
                .allSatisfy(m -> assertThat(m.getType()).isEqualTo(MovementType.INGRESO))
                .extracting(Movement::getSourceId)
                .containsExactlyInAnyOrder(contado.getId(), credito.getId());
    }

    @Test
    void lasComprasYSusPagosGeneranMovimientosPagoCompra() {
        Purchase contado = comprar("FAC-00001", com.servibox.backend.purchases.entity.PaymentType.CONTADO, caja);
        Purchase credito = comprar("FAC-00002", com.servibox.backend.purchases.entity.PaymentType.CREDITO, null);
        purchasesService.registrarPago(credito.getId(), caja.getId(), 19000.0);

        assertThat(deCategoria(MovementCategory.PAGO_COMPRA))
                .hasSize(2)
                .allSatisfy(m -> assertThat(m.getType()).isEqualTo(MovementType.EGRESO))
                .extracting(Movement::getSourceId)
                .containsExactlyInAnyOrder(contado.getId(), credito.getId());
    }

    @Test
    void editarOEliminarUnRecaudoDeVentaSeRechazaSinTocarNada() {
        vender("VEN-00003", com.servibox.backend.sales.entity.PaymentType.CONTADO, caja);
        Movement recaudo = deCategoria(MovementCategory.RECAUDO_VENTA).get(0);
        double saldo = saldoDe(caja);

        assertThatThrownBy(() -> treasuryService.editarMovimientoManual(
                recaudo.getId(), caja, "Editado", 1.0, null, null))
                .isInstanceOf(InvalidMovementOperationException.class)
                .hasMessage("El movimiento " + recaudo.getId()
                        + " es de categoria RECAUDO_VENTA y se gestiona desde su operacion");
        assertThatThrownBy(() -> treasuryService.eliminarMovimientoManual(recaudo.getId()))
                .isInstanceOf(InvalidMovementOperationException.class);

        assertThat(saldoDe(caja)).isEqualTo(saldo);
        assertThat(treasuryService.findMovementById(recaudo.getId())).get()
                .satisfies(m -> {
                    assertThat(m.getAmount()).isEqualTo(recaudo.getAmount());
                    assertThat(m.getConcept()).isEqualTo(recaudo.getConcept());
                });
    }

    @Test
    void editarOEliminarUnPagoDeCompraSeRechazaSinTocarNada() {
        comprar("FAC-00003", com.servibox.backend.purchases.entity.PaymentType.CONTADO, caja);
        Movement pago = deCategoria(MovementCategory.PAGO_COMPRA).get(0);
        double saldo = saldoDe(caja);

        assertThatThrownBy(() -> treasuryService.editarMovimientoManual(
                pago.getId(), caja, "Editado", 1.0, null, null))
                .isInstanceOf(InvalidMovementOperationException.class)
                .hasMessage("El movimiento " + pago.getId()
                        + " es de categoria PAGO_COMPRA y se gestiona desde su operacion");
        assertThatThrownBy(() -> treasuryService.eliminarMovimientoManual(pago.getId()))
                .isInstanceOf(InvalidMovementOperationException.class);

        assertThat(saldoDe(caja)).isEqualTo(saldo);
        assertThat(treasuryService.findMovementById(pago.getId())).get()
                .satisfies(m -> {
                    assertThat(m.getAmount()).isEqualTo(pago.getAmount());
                    assertThat(m.getConcept()).isEqualTo(pago.getConcept());
                });
    }

    /**
     * Lo mismo por HTTP y para las tres categorias no manuales: PUT y DELETE responden 400
     * y no cambian ni saldos ni filas.
     */
    @Test
    void porHttpEditarOEliminarUnMovimientoNoManualEs400YNoTocaNada() throws Exception {
        Account banco = fixture.crearCuenta("Bancolombia");
        vender("VEN-00004", com.servibox.backend.sales.entity.PaymentType.CONTADO, caja);
        comprar("FAC-00004", com.servibox.backend.purchases.entity.PaymentType.CONTADO, caja);
        TreasuryService.Transferencia transferencia =
                treasuryService.registrarTransferencia(caja, banco, "Consignacion", 50000.0, null);
        List<Movement> noManuales = List.of(
                deCategoria(MovementCategory.RECAUDO_VENTA).get(0),
                deCategoria(MovementCategory.PAGO_COMPRA).get(0),
                transferencia.egreso(),
                transferencia.ingreso());

        List<String> filasAntes = filas();
        double saldoCaja = saldoDe(caja);
        double saldoBanco = saldoDe(banco);
        String token = login();

        for (Movement movimiento : noManuales) {
            String mensaje = "El movimiento " + movimiento.getId() + " es de categoria "
                    + movimiento.getCategory() + " y se gestiona desde su operacion";
            mockMvc.perform(put("/api/treasury/movements/" + movimiento.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"accountId":%d,"category":"GASTO_OPERATIVO","concept":"Editado","amount":1}
                                    """.formatted(caja.getId())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(mensaje));
            mockMvc.perform(delete("/api/treasury/movements/" + movimiento.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(mensaje));
        }

        TenantContext.setTenantId(tenantId);
        assertThat(saldoDe(caja)).isEqualTo(saldoCaja);
        assertThat(saldoDe(banco)).isEqualTo(saldoBanco);
        assertThat(filas()).containsExactlyElementsOf(filasAntes);
    }

    /** Cada fila de MOVIMIENTOS del tenant, reducida a lo que editar o eliminar cambiaria. */
    private List<String> filas() {
        return treasuryService.buscarMovimientos(null, null, null, null).stream()
                .map(m -> m.getId() + "|" + m.getAccount().getId() + "|" + m.getCategory() + "|"
                        + m.getType() + "|" + m.getConcept() + "|" + m.getAmount() + "|" + m.getDate())
                .toList();
    }

    /** Crea un ADMIN en el tenant del test y devuelve su token. Deja el TenantContext limpio. */
    private String login() throws Exception {
        User user = new User();
        user.setUsername("admin");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        user.setRole(Role.ADMIN);
        userRepository.save(user);
        String slug = tenantRepository.findById(tenantId).orElseThrow().getSlug();
        TenantContext.clear();

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSlug":"%s","username":"admin","password":"%s"}
                                """.formatted(slug, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }
}
