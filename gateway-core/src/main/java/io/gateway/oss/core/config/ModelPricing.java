package io.gateway.oss.core.config;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

public class ModelPricing {

    @DecimalMin("0.0")
    private BigDecimal unitPrice;

    @DecimalMin("0.0")
    private BigDecimal inputUnitPrice;

    @DecimalMin("0.0")
    private BigDecimal outputUnitPrice;

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getInputUnitPrice() {
        return inputUnitPrice;
    }

    public void setInputUnitPrice(BigDecimal inputUnitPrice) {
        this.inputUnitPrice = inputUnitPrice;
    }

    public BigDecimal getOutputUnitPrice() {
        return outputUnitPrice;
    }

    public void setOutputUnitPrice(BigDecimal outputUnitPrice) {
        this.outputUnitPrice = outputUnitPrice;
    }
}
