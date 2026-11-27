// Don Yarmy Engineering: the delivery fee for a grocery client's orders.
//
// The Delivery Fees app calls this function for each order, from its SQL
// query, as delivery_fee(subtotal, distance_km, weekend). weekend is 1 for
// Saturday and Sunday and 0 otherwise. It returns the fee in dollars.
//
// To change the rule, edit this file, save it, and tap Reload rule in the
// app. The app reads the file, tries the rule on its sample orders, and
// stores it in its database, without being rebuilt.
(function (args) {
  const [subtotal, km, weekend] = args;
  // Orders of at least this much get free delivery.
  if (subtotal >= 50) return 0;
  // $3.99, plus 50 cents a km after the first 3 km.
  let fee = 3.99 + 0.5 * Math.max(0, km - 3);
  // Weekend deliveries cost extra.
  if (weekend) fee += 2;
  return Math.round(fee * 100) / 100;
})
