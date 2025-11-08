# Almost Realism Economics Module (`ar-economics`)

The Economics Module is currently a placeholder/stub module for future economic modeling and simulation capabilities. While the module infrastructure exists, most economic modeling code was removed in version 0.72.

## Current Status

**Active Development:** No
**Primary Use:** Dependency placeholder for ar-utils module
**Code Status:** Minimal (LICENSE file only)

## Historical Context

Previous versions (before 0.72) included economic modeling structures that were removed in commit 391095025 ("Removed uses of Scalar. Removed economics data structures"). The removed functionality included:

### Previously Implemented (Removed)

- **Currency Classes** - BTC, ETH, LTC, USD representations
- **Commodity** - Commodity abstraction for tradeable goods
- **Marketplace** - Market simulation framework
- **Security** - Financial securities modeling
- **Share** - Equity/share representation
- **MonetaryValue** - Monetary value handling with units
- **Expense/ExpenseRange** - Expense tracking and ranges
- **MarketplaceExpenseRange** - Market-specific expense modeling
- **Time-based Simulations** - Economic modeling over time
- **FloatingPointUnit** - Financial precision handling

## Future Potential

This module could be rebuilt to provide:

### Economic Simulations
- Agent-based economic models
- Market dynamics and equilibrium
- Supply and demand curves
- Price discovery mechanisms

### Financial Mathematics
- Option pricing (Black-Scholes, binomial models)
- Portfolio optimization
- Risk management (VaR, CVaR)
- Time value of money calculations

### Game Theory
- Nash equilibrium computation
- Strategic decision-making
- Auction mechanisms
- Cooperative and non-cooperative games

### Cryptocurrency/Blockchain
- Cryptocurrency modeling
- Transaction fee markets
- Mining economics
- DeFi protocol simulations

## Dependencies

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-algebra</artifactId>
    <version>0.72</version>
</dependency>
```

## Maven Dependency

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-economics</artifactId>
    <version>0.72</version>
</dependency>
```

## Contributing

If you're interested in rebuilding economic modeling capabilities in this module, consider:

1. **Starting with Fundamentals** - Currency, value, and basic market structures
2. **Agent-Based Models** - Economic agents with strategies and behaviors
3. **Market Mechanisms** - Order books, matching engines, price discovery
4. **Integration** - Use ar-algebra for mathematical operations, ar-stats for distributions
5. **Time Series** - Leverage ar-time for temporal economic data

Contact the Almost Realism development team if you'd like to contribute to rebuilding this module.
