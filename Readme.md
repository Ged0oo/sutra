# 🧥 Sutra (سُترة) — Complete System Architecture & Execution Blueprint
## 1. 🧩 Service Breakdown (Internal Modules)
Since this is a service-oriented monolith, each "service" is a well-bounded module (package) inside the single Spring Boot application. They communicate via direct method calls through well-defined interfaces — no HTTP between them, no message queues.

### Module 1: auth-module
| Aspect | Detail |
|---|---|
| Responsibility | User registration, login, token issuance, token refresh, password reset, role assignment |
| Key Operations | Register (Customer), Login, Refresh Token, Forgot/Reset Password, Logout (token blacklist) |
| Data Owned | users collection — credentials, hashed passwords, roles, account status, last login |
| Dependencies | notification-module (sends verification/reset emails) |
| Why It Exists | Centralizes all identity concerns. Every other module delegates authentication verification to this module's security filters. The Seller account is pre-seeded or created via a protected registration flow (invite-only or first-user-is-owner pattern), eliminating marketplace registration complexity. |
### Module 2: profile-module
| Aspect | Detail |
|---|---|
| Responsibility | User profile management, addresses, preferences (language, theme) |
| Key Operations | Get/Update profile, Manage shipping addresses (CRUD), Set language preference, Set theme preference |
| Data Owned | profiles collection — display name, phone, avatar URL, addresses array, preferences sub-document |
| Dependencies | auth-module (user identity), file-module (avatar upload) |
| Why It Exists | Separates identity (auth) from profile data. Addresses live here because they belong to the user, not to a specific order. Orders copy the address snapshot at order-time. |
### Module 3: catalog-module
| Aspect | Detail |
|---|---|
| Responsibility | Product lifecycle — creation, update, retrieval, filtering, search, categorization |
| Key Operations | Create/Update/Delete product, List products (paginated), Filter by category/size/color/price, Search (text-based), Manage categories, Manage product variants (size × color combinations) |
| Data Owned | products collection, categories collection |
| Dependencies | file-module (product images), inventory-module (reads stock for display), review-module (reads average rating for display) |
| Why It Exists | This is the heart of a clothing store. Product data is rich (localized names/descriptions, multiple images, variants). Keeping it isolated lets us optimize queries, caching, and indexing independently. Since Seller = Admin, all write operations are behind a single role guard — no vendor ownership logic needed. |
### Module 4: inventory-module
| Aspect | Detail |
|---|---|
| Responsibility | Stock tracking per product variant, stock reservation during checkout, low-stock alerts |
| Key Operations | Set stock quantity, Increment/Decrement stock, Reserve stock (on order placement), Release stock (on order cancellation/expiry), Get stock status, Low-stock threshold check |
| Data Owned | inventory collection — productId, variantKey (size+color), quantity, reservedQuantity, lowStockThreshold |
| Dependencies | catalog-module (variant definitions), notification-module (low-stock alerts to Seller) |
| Why It Exists | Separating inventory from the catalog is critical even in a monolith. Stock changes at a much higher frequency than product metadata. This isolation prevents write contention and allows independent caching strategies. Inventory writes need atomicity (MongoDB atomic operators) that shouldn't be tangled with catalog updates. |
### Module 5: cart-module
| Aspect | Detail |
|---|---|
| Responsibility | Shopping cart management — add/remove/update items, cart totals, cart persistence |
| Key Operations | Add item to cart, Remove item, Update quantity, Get cart, Clear cart, Merge guest cart on login |
| Data Owned | carts collection — userId (or sessionId for guests), items array (productId, variantKey, quantity, priceSnapshot), updatedAt |
| Dependencies | catalog-module (validate product exists, get current price), inventory-module (check availability) |
| Why It Exists | Cart is a transient-but-critical data structure. It needs its own collection because it's updated very frequently, needs TTL-based expiry for guest carts, and must snapshot prices at add-time (to detect price changes). Redis is used as the primary store for active carts (see caching section). |
### Module 6: order-module
| Aspect | Detail |
|---|---|
| Responsibility | Order creation, status management, order history, order lifecycle |
| Key Operations | Create order (from cart), Update order status (Pending → Confirmed → Shipped → Delivered / Cancelled), Get order details, List orders (Customer: own orders; Seller: all orders with filters), Cancel order |
| Data Owned | orders collection — orderId, customerId, items (snapshot), shippingAddress (snapshot), status, statusHistory, totals, paymentReference, timestamps |
| Dependencies | cart-module (reads cart to build order), inventory-module (reserves/releases stock), payment-module (payment processing), notification-module (order status updates), profile-module (shipping address) |
| Why It Exists | Orders are the transactional core. They must snapshot everything (product details, prices, address) at creation time so they remain accurate even if products or addresses change later. Status management is its own state machine. |
### Module 7: payment-module
| Aspect | Detail |
|---|---|
| Responsibility | Payment intent creation, Stripe webhook handling, payment status tracking, refund initiation |
| Key Operations | Create payment intent (Stripe), Handle Stripe webhooks (payment_succeeded, payment_failed, refund), Get payment status, Initiate refund |
| Data Owned | payments collection — paymentId, orderId, stripePaymentIntentId, amount, currency, status, refundStatus, timestamps |
| Dependencies | order-module (order reference), External: Stripe API |
| Why It Exists | Payment logic is isolated because it involves an external provider (Stripe), has its own error handling, retry logic, and webhook verification. This module is a boundary adapter — it translates Stripe concepts into internal domain events. |
### Module 8: wishlist-module
| Aspect | Detail |
|---|---|
| Responsibility | Wishlist management — add/remove products, list wishlist items |
| Key Operations | Add product to wishlist, Remove product, Get wishlist, Check if product is in wishlist |
| Data Owned | wishlists collection — userId, productIds array, timestamps per item |
| Dependencies | catalog-module (product validation, product details for display) |
| Why It Exists | Simple but distinct from cart. Wishlists are persistent (no expiry), have no quantity/variant concept, and serve a different user intent. Keeping it separate avoids polluting the cart module. |
### Module 9: review-module
| Aspect | Detail |
|---|---|
| Responsibility | Product reviews — submission, listing, moderation, rating aggregation |
| Key Operations | Submit review (rating + comment), List reviews for product (paginated), Delete review (Seller moderation or Customer own), Calculate average rating, Check if Customer already reviewed |
| Data Owned | reviews collection — reviewId, productId, customerId, rating, comment, createdAt, isVerifiedPurchase |
| Dependencies | order-module (verify purchase for "verified" badge), catalog-module (product reference), notification-module (notify Seller of new reviews) |
| Why It Exists | Reviews are user-generated content with their own lifecycle. They feed into product display (average rating) but are updated independently. Moderation logic (Seller can remove inappropriate reviews) is a distinct concern. |
### Module 10: notification-module
| Aspect | Detail |
|---|---|
| Responsibility | Centralized notification dispatch — in-app (WebSocket), email |
| Key Operations | Send in-app notification, Send email, Get user notifications (paginated), Mark as read, Notification preferences |
| Data Owned | notifications collection — recipientId, type, title, message (localized), isRead, channel, createdAt |
| Dependencies | External: Email service (SendGrid/SMTP), WebSocket infrastructure |
| Why It Exists | Every module that needs to communicate with users (order updates, low-stock alerts, new reviews, password resets) delegates to this single module. This prevents email/notification logic from scattering across the codebase. |
### Module 11: analytics-module
| Aspect | Detail |
|---|---|
| Responsibility | Seller dashboard data — sales metrics, revenue, order trends, product performance, inventory health |
| Key Operations | Get revenue by period (daily/weekly/monthly), Get order count & status breakdown, Get top-selling products, Get inventory health report, Get customer activity summary |
| Data Owned | analytics_snapshots collection (pre-computed daily/weekly aggregations) — period, revenue, orderCount, topProducts, etc. Also reads from orders, inventory, products via aggregation pipelines. |
| Dependencies | order-module, inventory-module, catalog-module (reads their data for aggregation) |
| Why It Exists | Analytics queries are expensive. This module owns the aggregation logic and caches results. It uses MongoDB aggregation pipelines on source collections plus pre-computed snapshots for historical data. Keeps dashboard API fast without burdening transactional modules. |
### Module 12: file-module
| Aspect | Detail |
|---|---|
| Responsibility | File upload orchestration — validation, upload to cloud storage, URL management |
| Key Operations | Upload image (validate type/size → upload to Cloudinary/S3 → return URL), Delete image, Generate optimized URLs (Cloudinary transformations) |
| Data Owned | No dedicated collection — file URLs are stored in the owning entity (product images in products, avatar in profiles). Optionally a file_registry collection for orphan cleanup. |
| Dependencies | External: Cloudinary or S3 |
| Why It Exists | Centralizes all file validation (type, size, dimensions), cloud provider interaction, and URL generation. If we switch from Cloudinary to S3, only this module changes. |
### Module 13: search-module
| Aspect | Detail |
|---|---|
| Responsibility | Product search — text search, autocomplete suggestions |
| Key Operations | Full-text search on product name/description (both languages), Autocomplete suggestions, Filter integration |
| Data Owned | No separate collection — uses MongoDB text indexes on products collection. Optionally maintains a search_terms collection for popular/recent searches. |
| Dependencies | catalog-module (product data) |
| Why It Exists | Encapsulates search logic (text index queries, scoring, relevance) separately from basic catalog CRUD. If search needs ever grow (Elasticsearch), this module is the only change point. For MVP, MongoDB Atlas Search or text indexes are sufficient for a single-brand store's catalog size. |
## 2. 🏗️ System Architecture (High-Level)
### 2.1 Overall Topology
```text
┌──────────────────────────────────────────────────────┐
│                    CLIENTS                            │
│  ┌─────────────┐  ┌─────────────┐  ┌──────────────┐ │
│  │ Customer SPA │  │ Seller SPA  │  │ Mobile (PWA) │ │
│  │  (Angular)   │  │  (Angular)  │  │   (future)   │ │
│  └──────┬───────┘  └──────┬──────┘  └──────┬───────┘ │
└─────────┼─────────────────┼─────────────────┼────────┘
```
          │                 │                 │
          ▼                 ▼                 ▼
┌─────────────────────────────────────────────────────┐
│              NGINX / Cloud Load Balancer             │
│         (SSL termination, static file serving,       │
│          rate limiting, gzip, WebSocket upgrade)     │
└────────────────────────┬────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────┐
│           SPRING BOOT MONOLITH (Single JVM)         │
│                                                     │
│  ┌─────────────────────────────────────────────┐    │
│  │            Security Filter Chain             │    │
│  │  (JWT validation, role extraction, CORS)     │    │
│  └──────────────────────┬──────────────────────┘    │
│                         │                           │
│  ┌──────────────────────▼──────────────────────┐    │
│  │             REST Controller Layer            │    │
│  │  /api/v1/auth    /api/v1/products            │    │
│  │  /api/v1/cart    /api/v1/orders              │    │
│  │  /api/v1/wishlist /api/v1/reviews            │    │
│  │  /api/v1/seller/** (protected)               │    │
│  │  /api/v1/notifications                       │    │
│  └──────────────────────┬──────────────────────┘    │
│                         │                           │
│  ┌──────────────────────▼──────────────────────┐    │
│  │             Service Layer (Modules)          │    │
│  │                                              │    │
│  │  ┌────────┐ ┌─────────┐ ┌──────────┐       │    │
│  │  │  Auth  │ │ Catalog │ │Inventory │       │    │
│  │  └───┬────┘ └────┬────┘ └────┬─────┘       │    │
│  │      │           │           │              │    │
│  │  ┌───┴────┐ ┌────┴────┐ ┌───┴──────┐      │    │
│  │  │Profile │ │  Cart   │ │  Order   │      │    │
│  │  └────────┘ └─────────┘ └────┬─────┘      │    │
│  │                              │              │    │
│  │  ┌────────┐ ┌─────────┐ ┌───┴──────┐      │    │
│  │  │Wishlist│ │ Review  │ │ Payment  │      │    │
│  │  └────────┘ └─────────┘ └──────────┘      │    │
│  │                                              │    │
│  │  ┌────────────┐ ┌──────────┐ ┌──────────┐  │    │
│  │  │Notification│ │Analytics │ │  File    │  │    │
│  │  └────────────┘ └──────────┘ └──────────┘  │    │
│  │                               ┌──────────┐  │    │
│  │                               │  Search  │  │    │
│  │                               └──────────┘  │    │
│  └──────────────────────────────────────────────┘    │
│                                                     │
│  ┌─────────────────────────────────────────────┐    │
│  │        WebSocket Handler (STOMP/SockJS)     │    │
│  │     /ws/notifications                        │    │
│  └─────────────────────────────────────────────┘    │
│                                                     │
│  ┌─────────────────────────────────────────────┐    │
│  │        Scheduled Tasks (Spring @Scheduled)  │    │
│  │  - Analytics snapshot generation (daily)     │    │
│  │  - Expired cart cleanup                      │    │
│  │  - Expired reservation release               │    │
│  │  - Token blacklist cleanup                   │    │
│  └─────────────────────────────────────────────┘    │
└──────────┬──────────────┬──────────────┬────────────┘
           │              │              │
           ▼              ▼              ▼
     ┌──────────┐  ┌──────────┐  ┌──────────────┐
     │ MongoDB  │  │  Redis   │  │  Cloudinary  │
     │ (Atlas)  │  │          │  │   / S3       │
     └──────────┘  └──────────┘  └──────────────┘
                                        │
                                  ┌─────┴──────┐
                                  │   Stripe   │
                                  │   (API)    │
                                  └────────────┘
### 2.2 Frontend ↔ Backend Interaction
```text
Angular App
    │
    ├── HTTP Interceptor
    │   ├── Attaches JWT (Authorization: Bearer <token>)
    │   ├── Attaches Accept-Language header (ar / en)
    │   ├── Handles 401 → Token refresh flow
    │   └── Handles errors globally
    │
    ├── REST calls → /api/v1/**
    │   └── JSON request/response
    │
    ├── WebSocket connection → /ws/**
    │   └── STOMP over SockJS
    │   └── Subscribes to /user/queue/notifications
    │
    └── File uploads → multipart/form-data → /api/v1/files/upload
```
#### Key Design Points

Angular has two route groups: Customer-facing pages and Seller dashboard pages
Both are in the same Angular app (lazy-loaded route modules), guarded by role-based route guards
The backend identifies the role from the JWT and enforces authorization at the controller/service level
### 2.3 Internal Module Communication
Inside the monolith, modules call each other via injected service interfaces:

```text
OrderService
    │
    ├──► CartService.getCart(userId)          // reads cart
    ├──► InventoryService.reserveStock(...)   // reserves inventory
    ├──► PaymentService.createIntent(...)     // initiates payment
    ├──► ProfileService.getAddress(...)       // gets shipping address
    └──► NotificationService.send(...)        // sends notifications
```
#### Rules

Modules communicate through interfaces (not concrete classes directly)
No circular dependencies — dependency direction is enforced:
auth ← everything (for user context)
notification ← everything (for sending)
file ← catalog, profile (for uploads)
catalog ← cart, wishlist, search, analytics
inventory ← cart, order, analytics
order → cart, inventory, payment, notification, profile
payment → order (bidirectional via event-like callbacks)
analytics → order, inventory, catalog (read-only)
### 2.4 Redis Usage Map
```text
Redis
 │
 ├── Active Carts (primary store)
 │   Key: cart:{userId}  |  TTL: 7 days
 │
 ├── Session/Token Blacklist
 │   Key: blacklist:{jti}  |  TTL: matches token expiry
 │
 ├── Product Cache
 │   Key: product:{id}  |  TTL: 15 min
 │   Key: products:featured  |  TTL: 10 min
 │   Key: categories:all  |  TTL: 1 hour
 │
 ├── Inventory Stock Cache
 │   Key: stock:{productId}:{variant}  |  TTL: 2 min (short!)
 │
 ├── Analytics Cache
 │   Key: analytics:dashboard:{period}  |  TTL: 30 min
 │
 ├── Rate Limiting
 │   Key: rate:{ip}:{endpoint}  |  TTL: window size
 │
 └── Search Suggestions Cache
     Key: search:suggest:{prefix}  |  TTL: 5 min
```
### 2.5 File Storage Flow
```text
Client                     Backend                    Cloudinary/S3
  │                          │                            │
  │  POST /api/v1/files      │                            │
  │  multipart/form-data     │                            │
  │ ──────────────────────►  │                            │
  │                          │  1. Validate file           │
  │                          │     - type (jpg/png/webp)   │
  │                          │     - size (< 5MB)          │
  │                          │     - dimensions             │
  │                          │                            │
  │                          │  2. Upload to cloud         │
  │                          │ ─────────────────────────► │
  │                          │                            │
  │                          │  3. Receive URL + publicId  │
  │                          │ ◄───────────────────────── │
  │                          │                            │
  │  { url, publicId }       │                            │
  │ ◄──────────────────────  │                            │
  │                          │                            │
  │  PUT /api/v1/products/x  │                            │
  │  { images: [url1, url2]} │                            │
  │ ──────────────────────►  │                            │
Two-step approach: Upload files first → get URLs → attach URLs to entity. This prevents orphan files in error cases and simplifies the entity update logic.

```
### 2.6 WebSocket Design (see Section 7 for details)
```text
Client connects to /ws with JWT as query param or in STOMP headers
    │
    ▼
Spring WebSocket Handler validates JWT
    │
    ▼
Client subscribes to /user/queue/notifications
    │
    ▼
Backend modules trigger NotificationService.send(userId, event)
    │
    ▼
NotificationService:
  1. Persists to MongoDB (notifications collection)
  2. Publishes to user's WebSocket queue (if connected)
  3. Optionally sends email (based on notification type + user preferences)
```
## 3. 🗄️ Data Ownership & Boundaries
### MongoDB Collections Map
```text
Database: sutra_db
│
├── auth-module
│   └── users
│       {
│         _id, email, passwordHash, role ("CUSTOMER"|"SELLER"),
│         isEmailVerified, isActive, refreshTokenHash,
│         createdAt, updatedAt, lastLoginAt
│       }
│
├── profile-module
│   └── profiles
│       {
│         _id, userId (ref→users), displayName,
│         phone, avatarUrl,
│         addresses: [{ id, label, street, city, state, zip, country, isDefault }],
│         preferences: { language: "ar"|"en", theme: "dark"|"light" },
│         createdAt, updatedAt
│       }
│
├── catalog-module
│   ├── products
│   │   {
│   │     _id, sku,
│   │     name: { en, ar }, description: { en, ar },
│   │     categoryId (ref→categories),
│   │     price, compareAtPrice (for discounts),
│   │     images: [{ url, publicId, isPrimary, sortOrder }],
│   │     variants: [{ sku, size, color: { name: {en,ar}, hex }, isActive }],
│   │     tags: [],
│   │     averageRating, reviewCount,   ← denormalized from reviews
│   │     isActive, isFeatured,
│   │     createdAt, updatedAt
│   │   }
│   │
│   └── categories
│       {
│         _id, name: { en, ar }, slug, description: { en, ar },
│         parentId (nullable, for subcategories),
│         image: { url, publicId },
│         sortOrder, isActive,
│         createdAt, updatedAt
│       }
│
├── inventory-module
│   └── inventory
│       {
│         _id, productId (ref→products),
│         variantSku,
│         quantity, reservedQuantity,
│         lowStockThreshold (default: 5),
│         updatedAt
│       }
│       Index: { productId: 1, variantSku: 1 } UNIQUE
│
├── cart-module
│   └── carts  (MongoDB as fallback/persistence; Redis is primary)
│       {
│         _id, userId (or guestSessionId),
│         items: [{
│           productId, variantSku, quantity,
│           priceSnapshot, productNameSnapshot: { en, ar },
│           imageUrl, addedAt
│         }],
│         updatedAt, expiresAt
│       }
│
├── order-module
│   └── orders
│       {
│         _id, orderNumber (human-readable: "STR-20250101-0001"),
│         customerId (ref→users),
│         items: [{
│           productId, variantSku, productName: {en,ar},
│           size, color, quantity, unitPrice, totalPrice, imageUrl
│         }],
│         shippingAddress: { embedded snapshot },
│         subtotal, shippingCost, tax, totalAmount,
│         currency: "SAR",
│         status: "PENDING"|"CONFIRMED"|"PROCESSING"|"SHIPPED"|"DELIVERED"|"CANCELLED",
│         statusHistory: [{ status, timestamp, note }],
│         paymentId (ref→payments),
│         notes,
│         createdAt, updatedAt
│       }
│       Index: { customerId: 1, createdAt: -1 }
│       Index: { status: 1 }
│       Index: { orderNumber: 1 } UNIQUE
│
├── payment-module
│   └── payments
│       {
│         _id, orderId (ref→orders),
│         stripePaymentIntentId, stripeCustomerId,
│         amount, currency,
│         status: "PENDING"|"SUCCEEDED"|"FAILED"|"REFUNDED"|"PARTIALLY_REFUNDED",
│         refundAmount,
│         stripeWebhookEvents: [{ eventType, timestamp, raw }],
│         createdAt, updatedAt
│       }
│
├── wishlist-module
│   └── wishlists
│       {
│         _id, userId (ref→users),
│         items: [{ productId, addedAt }],
│         updatedAt
│       }
│       Index: { userId: 1 } UNIQUE
│
├── review-module
│   └── reviews
│       {
│         _id, productId (ref→products),
│         customerId (ref→users),
│         rating (1-5), comment,
│         isVerifiedPurchase,
│         createdAt, updatedAt
│       }
│       Index: { productId: 1, createdAt: -1 }
│       Index: { productId: 1, customerId: 1 } UNIQUE (one review per product per customer)
│
├── notification-module
│   └── notifications
│       {
│         _id, recipientId (ref→users),
│         type: "ORDER_STATUS"|"LOW_STOCK"|"NEW_REVIEW"|"PAYMENT"|"SYSTEM",
│         title: { en, ar }, message: { en, ar },
│         data: { orderId, productId, ... },  ← contextual payload
│         isRead, readAt,
│         channel: "IN_APP"|"EMAIL"|"BOTH",
│         createdAt
│       }
│       Index: { recipientId: 1, isRead: 1, createdAt: -1 }
│
├── analytics-module
│   └── analytics_snapshots
│       {
│         _id, period: "DAILY"|"WEEKLY"|"MONTHLY",
│         date,  ← the date/week/month this snapshot represents
│         revenue, orderCount, averageOrderValue,
│         topProducts: [{ productId, name, unitsSold, revenue }],
│         ordersByStatus: { pending, confirmed, shipped, delivered, cancelled },
│         newCustomers,
│         createdAt
│       }
│       Index: { period: 1, date: -1 } UNIQUE
│
└── search-module
    └── search_terms (optional)
        {
          _id, term, count, lastSearchedAt
        }
```
### Separation Principles
Each module owns its writes — only the owning module writes to its collection
Cross-reads are allowed — analytics reads from orders/inventory via aggregation
Denormalization is intentional — averageRating on products, address snapshots in orders
References use userId/productId — not MongoDB $lookup joins in hot paths (those are resolved at the service layer or cached)
## 4. 🔄 Request Flows
#### Flow 1: Customer Registration
```text
1. Client POST /api/v1/auth/register { email, password, displayName, language }
2. Auth Module:
   a. Validate input (email format, password strength)
   b. Check email uniqueness in `users` collection
   c. Hash password (BCrypt)
   d. Create user document { role: "CUSTOMER", isEmailVerified: false }
   e. Generate email verification token (JWT, short-lived)
3. Profile Module:
   a. Create profile document { userId, displayName, preferences: { language } }
4. Notification Module:
   a. Send verification email with token link
5. Response: 201 Created { message: "Verification email sent" }
6. Customer clicks link → GET /api/v1/auth/verify-email?token=xxx
7. Auth Module validates token, sets isEmailVerified: true
8. Response: 200 OK → redirect to login
```
#### Flow 2: Login
```text
1. Client POST /api/v1/auth/login { email, password }
2. Auth Module:
   a. Find user by email
   b. Verify password against hash
   c. Check isActive & isEmailVerified
   d. Generate Access Token (JWT, 15min, contains userId + role)
   e. Generate Refresh Token (JWT, 7 days, stored hash in DB)
   f. Update lastLoginAt
3. Response: 200 OK { accessToken, refreshToken, user: { id, role, displayName } }
4. Client stores tokens:
   - Access token → memory (or short-lived cookie)
   - Refresh token → HttpOnly cookie (preferred) or secure storage
5. Client establishes WebSocket connection with access token
```
#### Flow 3: Add to Cart
```text
1. Client POST /api/v1/cart/items { productId, variantSku, quantity }
2. Security Filter: Validate JWT, extract userId
3. Cart Module:
   a. Validate product exists via Catalog Module
   b. Validate variant exists and is active
   c. Check stock availability via Inventory Module
   d. Check if item already in cart:
      - Yes: Update quantity (validate new total ≤ available stock)
      - No: Add new item with price snapshot
   e. Recalculate cart totals
   f. Write to Redis (primary) + async persist to MongoDB (backup)
4. Response: 200 OK { cart: { items, subtotal, itemCount } }
```
#### Flow 4: Place Order + Payment
```text
1. Client POST /api/v1/orders { addressId, notes? }
2. Security Filter: Validate JWT (CUSTOMER role)
3. Order Module orchestrates:
   a. Cart Module: Fetch full cart → validate not empty
   b. Profile Module: Fetch shipping address by addressId → validate exists
   c. Catalog Module: Re-validate all products are active, re-fetch current prices
   d. Price Change Check: Compare cart snapshots with current prices
      - If prices changed → return 409 Conflict with details
   e. Inventory Module: Reserve stock for ALL items (atomic per item)
      - If any item out of stock → rollback all reservations → return 409
   f. Create order document:
      - Status: PENDING
      - Snapshot everything (items, address, prices)
      - Generate orderNumber
   g. Payment Module: Create Stripe PaymentIntent
      - Amount = order total
      - Metadata = orderId
      - Returns clientSecret
   h. Update order with paymentId
4. Response: 200 OK { orderId, orderNumber, stripeClientSecret }
5. Client uses Stripe.js to confirm payment with clientSecret
6. Stripe fires webhook → POST /api/v1/webhooks/stripe
7. Payment Module:
   a. Verify webhook signature
   b. Parse event type:
      - payment_intent.succeeded:
        i.  Update payment status → SUCCEEDED
        ii. Order Module: Update order status → CONFIRMED
        iii. Inventory Module: Commit reservation (deduct from quantity)
        iv. Cart Module: Clear cart
        v.  Notification Module: Notify customer (order confirmed)
        vi. Notification Module: Notify seller (new order) + WebSocket push
      - payment_intent.payment_failed:
        i.  Update payment status → FAILED
        ii. Order Module: Update order status → CANCELLED
        iii. Inventory Module: Release reservations
        iv. Notification Module: Notify customer (payment failed)
```
#### Flow 5: Wishlist Actions
```text
ADD TO WISHLIST:
1. Client POST /api/v1/wishlist/items { productId }
2. Security Filter: JWT validation
3. Wishlist Module:
   a. Validate product exists and is active (Catalog Module)
   b. Check if already in wishlist → if yes, return 200 (idempotent)
   c. Add to wishlist items array
4. Response: 200 OK { wishlist }

REMOVE FROM WISHLIST:
1. Client DELETE /api/v1/wishlist/items/{productId}
2. Wishlist Module: Remove from array
3. Response: 200 OK

MOVE TO CART:
1. Client POST /api/v1/wishlist/items/{productId}/move-to-cart { variantSku, quantity }
2. Wishlist Module: Remove from wishlist
3. Cart Module: Add to cart (same flow as "Add to Cart")
4. Response: 200 OK
```
#### Flow 6: Reviews
```text
SUBMIT REVIEW:
1. Client POST /api/v1/products/{productId}/reviews { rating, comment }
2. Security Filter: JWT validation (CUSTOMER)
3. Review Module:
   a. Validate product exists (Catalog Module)
   b. Check customer hasn't already reviewed this product
   c. Check if customer purchased this product (Order Module)
      → Sets isVerifiedPurchase flag
   d. Create review document
   e. Recalculate averageRating and reviewCount
   f. Update product's denormalized fields (Catalog Module)
   g. Notify seller of new review (Notification Module)
4. Response: 201 Created { review }
```
#### Flow 7: Seller Managing Products & Inventory
```text
CREATE PRODUCT:
1. Seller uploads images first: POST /api/v1/files/upload (multiple calls)
   → Returns array of { url, publicId }
2. Seller POST /api/v1/seller/products {
     name: { en, ar }, description: { en, ar },
     categoryId, price, variants: [...], images: [{ url, publicId }]
   }
3. Security Filter: JWT validation (SELLER role required)
4. Catalog Module:
   a. Validate category exists
   b. Validate variants (valid sizes, colors)
   c. Generate SKUs for variants
   d. Create product document (isActive: false initially — draft)
5. Inventory Module:
   a. Create inventory records for each variant (quantity: 0)
6. Response: 201 Created { product }

UPDATE INVENTORY:
1. Seller PUT /api/v1/seller/inventory/{productId} {
     updates: [{ variantSku, quantity, lowStockThreshold }]
   }
2. Security Filter: SELLER role
3. Inventory Module:
   a. Update quantities for each variant
   b. Invalidate Redis stock cache
   c. Check if any variant went from 0 → positive (back in stock)
      → Notification Module: optionally notify customers who wishlisted it
4. Response: 200 OK { updatedInventory }

MANAGE ORDER STATUS:
1. Seller PUT /api/v1/seller/orders/{orderId}/status { status: "SHIPPED", note? }
2. Security Filter: SELLER role
3. Order Module:
   a. Validate status transition (state machine):
      PENDING → CONFIRMED → PROCESSING → SHIPPED → DELIVERED
      PENDING → CANCELLED
      CONFIRMED → CANCELLED
      (any other transition → 400 Bad Request)
   b. Update status + append to statusHistory with timestamp and note
   c. If CANCELLED:
      i.   Inventory Module: Release reserved stock
      ii.  Payment Module: Initiate refund via Stripe
   d. Notification Module:
      i.   Push in-app notification to customer via WebSocket
      ii.  Send email notification with order details and new status
4. Response: 200 OK { order with updated status }

VIEW ALL ORDERS (Seller):
1. Seller GET /api/v1/seller/orders?status=CONFIRMED&page=1&size=20&sort=createdAt,desc
2. Security Filter: SELLER role
3. Order Module:
   a. Query orders collection with filters
   b. Paginate results
   c. Enrich with customer display name (Profile Module)
4. Response: 200 OK { orders[], totalCount, totalPages, currentPage }

ACTIVATE/DEACTIVATE PRODUCT:
1. Seller PATCH /api/v1/seller/products/{productId}/status { isActive: true }
2. Security Filter: SELLER role
3. Catalog Module:
   a. Update isActive flag
   b. Invalidate Redis product cache
   c. If deactivating: Cart Module removes this product from all active carts
      (or flags items as unavailable on next cart retrieval)
4. Response: 200 OK { product }
```
## 5. ⚡ Performance & Caching Strategy
### 5.1 Redis Caching Architecture
```text
┌────────────────────────────────────────────────────────────┐
│                     REDIS INSTANCE                         │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  LAYER 1: HOT DATA (Primary Store)                   │  │
│  │                                                      │  │
│  │  cart:{userId}           → Full cart JSON             │  │
│  │  TTL: 7 days             → PRIMARY store, MongoDB     │  │
│  │                            is async backup            │  │
│  │                                                      │  │
│  │  blacklist:{jti}         → "1"                       │  │
│  │  TTL: matches token exp  → Logged-out token IDs      │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  LAYER 2: READ CACHE (Cache-Aside Pattern)           │  │
│  │                                                      │  │
│  │  product:{id}            → Product JSON               │  │
│  │  TTL: 15 min                                          │  │
│  │                                                      │  │
│  │  product:{id}:{lang}     → Localized product view     │  │
│  │  TTL: 15 min                                          │  │
│  │                                                      │  │
│  │  products:featured       → Featured products list     │  │
│  │  TTL: 10 min                                          │  │
│  │                                                      │  │
│  │  products:category:{id}:page:{n}                      │  │
│  │  TTL: 10 min             → Paginated category views   │  │
│  │                                                      │  │
│  │  categories:tree         → Full category hierarchy    │  │
│  │  TTL: 1 hour                                          │  │
│  │                                                      │  │
│  │  product:{id}:reviews:page:{n}                        │  │
│  │  TTL: 10 min             → Paginated reviews          │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  LAYER 3: SHORT-LIVED VOLATILE DATA                  │  │
│  │                                                      │  │
│  │  stock:{productId}:{variantSku}                       │  │
│  │  TTL: 2 min              → Stock count (short TTL     │  │
│  │                            because accuracy matters)  │  │
│  │                                                      │  │
│  │  rate:{ip}:{endpoint}    → Request count              │  │
│  │  TTL: 60 sec             → Sliding window rate limit  │  │
│  │                                                      │  │
│  │  search:suggest:{prefix} → Autocomplete results      │  │
│  │  TTL: 5 min                                           │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  LAYER 4: ANALYTICS CACHE                            │  │
│  │                                                      │  │
│  │  analytics:dashboard:daily                            │  │
│  │  TTL: 30 min             → Today's dashboard data     │  │
│  │                                                      │  │
│  │  analytics:dashboard:weekly                           │  │
│  │  TTL: 1 hour             → Weekly summary             │  │
│  │                                                      │  │
│  │  analytics:dashboard:monthly                          │  │
│  │  TTL: 2 hours            → Monthly summary            │  │
│  │                                                      │  │
│  │  analytics:topProducts:{period}                       │  │
│  │  TTL: 1 hour             → Top products ranking       │  │
│  └──────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────┘
```
### 5.2 Caching Patterns Per Module
| Module | Pattern | Reasoning |
|---|---|---|
| Cart | Write-through to Redis, async persist to MongoDB | Carts are accessed/modified frequently. Redis gives sub-ms reads. MongoDB backup prevents data loss on Redis failure |
| Catalog | Cache-aside (lazy load) | Read-heavy, write-infrequent. On cache miss → read MongoDB → populate cache. On product update → invalidate cache |
| Inventory | Cache-aside with short TTL (2 min) | Stock accuracy is more important than speed. Short TTL ensures near-real-time accuracy. During checkout, always read from MongoDB (skip cache) |
| Analytics | Cache-aside with longer TTL | Analytics data changes slowly (aggregated). Expensive to compute. Cache for 30min–2hrs depending on granularity |
| Auth | Token blacklist in Redis | Fast lookup on every authenticated request. TTL auto-cleans expired tokens |
| Categories | Cache-aside with long TTL (1 hour) | Rarely changes. Loaded on every page (navigation menu) |
### 5.3 Cache Invalidation Strategy
```text
┌─────────────────────────────────────────────────────────────┐
│               CACHE INVALIDATION TRIGGERS                   │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  PRODUCT UPDATED/CREATED/DELETED (by Seller):               │
│    → Delete: product:{id}                                   │
│    → Delete: product:{id}:*  (all localized versions)       │
│    → Delete: products:featured                              │
│    → Delete: products:category:{categoryId}:*               │
│                                                             │
│  INVENTORY CHANGED:                                         │
│    → Delete: stock:{productId}:{variantSku}                 │
│    (Short TTL handles most cases; explicit delete on        │
│     order placement/cancellation for immediate accuracy)    │
│                                                             │
│  REVIEW SUBMITTED/DELETED:                                  │
│    → Delete: product:{id}:reviews:*                         │
│    → Delete: product:{id}  (because averageRating changed)  │
│                                                             │
│  CATEGORY CHANGED:                                          │
│    → Delete: categories:tree                                │
│    → Delete: products:category:{id}:*                       │
│                                                             │
│  ORDER STATUS CHANGED:                                      │
│    → Delete: analytics:dashboard:*  (analytics recalc)      │
│    (Or let TTL expire naturally — acceptable delay for       │
│     analytics data)                                         │
│                                                             │
│  USER LOGOUT:                                               │
│    → Set: blacklist:{jti} with TTL = remaining token life   │
│    → Delete: cart:{userId} from Redis (optional)            │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```
### 5.4 MongoDB Performance Optimizations
```text
INDEX STRATEGY:
│
├── products collection
│   ├── { "name.en": "text", "name.ar": "text", "description.en": "text", "description.ar": "text" }  → Text search
│   ├── { categoryId: 1, isActive: 1, createdAt: -1 }  → Category listing
│   ├── { isActive: 1, isFeatured: 1 }                  → Featured products
│   ├── { price: 1 }                                     → Price filtering
│   └── { tags: 1 }                                      → Tag-based filtering
│
├── orders collection
│   ├── { customerId: 1, createdAt: -1 }    → Customer order history
│   ├── { status: 1, createdAt: -1 }        → Seller order management
│   ├── { orderNumber: 1 } UNIQUE            → Order lookup
│   └── { createdAt: -1 }                   → Analytics queries
│
├── inventory collection
│   └── { productId: 1, variantSku: 1 } UNIQUE  → Stock lookup
│
├── reviews collection
│   ├── { productId: 1, createdAt: -1 }          → Product reviews listing
│   └── { productId: 1, customerId: 1 } UNIQUE   → One review per customer
│
├── notifications collection
│   └── { recipientId: 1, isRead: 1, createdAt: -1 }  → User notification feed
│
└── analytics_snapshots collection
    └── { period: 1, date: -1 } UNIQUE  → Dashboard queries

QUERY OPTIMIZATION:
  - Projection: Only fetch needed fields in list queries
  - Pagination: cursor-based for infinite scroll, offset for seller dashboard
  - Aggregation pipelines: Used only in analytics (off-peak or pre-computed)
  - Read preference: primaryPreferred (if replica set)
```
## 6. 🔐 Security Design
### 6.1 JWT Authentication Flow
```text
┌────────────────────────────────────────────────────────────────┐
│                      JWT TOKEN DESIGN                          │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  ACCESS TOKEN (Short-lived: 15 minutes)                        │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ Header:  { alg: "HS256", typ: "JWT" }                   │  │
│  │ Payload: {                                               │  │
│  │   sub: "userId",                                         │  │
│  │   role: "CUSTOMER" | "SELLER",                           │  │
│  │   jti: "unique-token-id",    ← for blacklisting          │  │
│  │   iat: issued-at,                                         │  │
│  │   exp: expiry (15min)                                     │  │
│  │ }                                                         │  │
│  │ Signature: HMAC-SHA256(secret)                            │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                │
│  REFRESH TOKEN (Long-lived: 7 days)                            │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ Payload: {                                               │  │
│  │   sub: "userId",                                         │  │
│  │   jti: "unique-refresh-id",                               │  │
│  │   type: "REFRESH",                                        │  │
│  │   exp: expiry (7 days)                                    │  │
│  │ }                                                         │  │
│  │ → Hash stored in users collection (not raw token)         │  │
│  │ → Sent via HttpOnly, Secure, SameSite cookie              │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                │
│  TOKEN REFRESH FLOW:                                           │
│  1. Client detects 401 (access token expired)                  │
│  2. Angular interceptor sends POST /api/v1/auth/refresh        │
│     (refresh token sent automatically via cookie)              │
│  3. Backend validates refresh token:                           │
│     a. Verify JWT signature and expiry                         │
│     b. Compare hash with stored hash in DB                     │
│     c. Check if user is still active                           │
│     d. Check blacklist in Redis                                │
│  4. Issue new access token + rotate refresh token              │
│  5. Invalidate old refresh token hash                          │
│  6. Return new access token                                    │
│                                                                │
│  LOGOUT FLOW:                                                  │
│  1. POST /api/v1/auth/logout                                   │
│  2. Add access token's jti to Redis blacklist                  │
│  3. Delete refresh token hash from users collection            │
│  4. Clear refresh token cookie                                 │
│  5. Disconnect WebSocket                                       │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```
### 6.2 Security Filter Chain
```text
Every Request
    │
    ▼
┌─────────────────────┐
│  CORS Filter         │  → Allow frontend origin, credentials, specific headers
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│  Rate Limit Filter   │  → Check Redis counter per IP per endpoint
│                     │     → 429 Too Many Requests if exceeded
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│  JWT Auth Filter     │  → Extract token from Authorization header
│                     │  → Validate signature + expiry
│                     │  → Check blacklist in Redis
│                     │  → Set SecurityContext (userId, role)
│                     │  → Skip for public endpoints (/api/v1/auth/**, 
│                     │     GET /api/v1/products/**)
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│  Role Authorization  │  → /api/v1/seller/** requires SELLER role
│                     │  → /api/v1/cart/**, /api/v1/orders/** require CUSTOMER
│                     │  → /api/v1/webhooks/stripe — Stripe signature verification
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│  Controller          │  → Processes request
└─────────────────────┘
```
### 6.3 Role-Based Access Matrix
```text
┌──────────────────────────────┬────────────┬────────────┬────────────┐
│         ENDPOINT             │   PUBLIC   │  CUSTOMER  │   SELLER   │
├──────────────────────────────┼────────────┼────────────┼────────────┤
│ POST /auth/register          │     ✅     │     —      │     —      │
│ POST /auth/login             │     ✅     │     —      │     —      │
│ POST /auth/refresh           │     ✅     │     —      │     —      │
│ GET  /products               │     ✅     │     ✅     │     ✅     │
│ GET  /products/{id}          │     ✅     │     ✅     │     ✅     │
│ GET  /categories             │     ✅     │     ✅     │     ✅     │
│ GET  /products/{id}/reviews  │     ✅     │     ✅     │     ✅     │
│ POST /cart/**                │     —      │     ✅     │     —      │
│ POST /wishlist/**            │     —      │     ✅     │     —      │
│ POST /orders                 │     —      │     ✅     │     —      │
│ GET  /orders (own)           │     —      │     ✅     │     —      │
│ POST /products/{id}/reviews  │     —      │     ✅     │     —      │
│ GET  /notifications          │     —      │     ✅     │     ✅     │
│ ALL  /seller/products/**     │     —      │     —      │     ✅     │
│ ALL  /seller/inventory/**    │     —      │     —      │     ✅     │
│ ALL  /seller/orders/**       │     —      │     —      │     ✅     │
│ ALL  /seller/analytics/**    │     —      │     —      │     ✅     │
│ POST /webhooks/stripe        │  Stripe ✅ │     —      │     —      │
└──────────────────────────────┴────────────┴────────────┴────────────┘
```
### 6.4 Stripe Payment Security Flow
```text
┌──────────────────────────────────────────────────────────────────────┐
│                   STRIPE INTEGRATION SECURITY                        │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  PRINCIPLE: The customer's browser NEVER sees the full payment flow  │
│  Server creates the intent, client only confirms it.                 │
│                                                                      │
│  ┌────────┐        ┌──────────┐        ┌──────────┐                 │
│  │ Client │        │ Backend  │        │  Stripe  │                 │
│  └───┬────┘        └────┬─────┘        └────┬─────┘                 │
│      │                  │                   │                        │
│      │ Place Order      │                   │                        │
│      │─────────────────►│                   │                        │
│      │                  │ Create Payment    │                        │
│      │                  │ Intent            │                        │
│      │                  │──────────────────►│                        │
│      │                  │                   │                        │
│      │                  │ { clientSecret,   │                        │
│      │                  │   intentId }      │                        │
│      │                  │◄──────────────────│                        │
│      │                  │                   │                        │
│      │ { clientSecret } │                   │                        │
│      │◄─────────────────│                   │                        │
│      │                  │                   │                        │
│      │ stripe.confirmPayment(clientSecret)  │                        │
│      │─────────────────────────────────────►│                        │
│      │                  │                   │                        │
│      │ 3D Secure / Auth │                   │                        │
│      │◄────────────────────────────────────►│                        │
│      │                  │                   │                        │
│      │                  │   Webhook:        │                        │
│      │                  │   payment_intent  │                        │
│      │                  │   .succeeded      │                        │
│      │                  │◄──────────────────│                        │
│      │                  │                   │                        │
│      │                  │ Verify webhook    │                        │
│      │                  │ signature using   │                        │
│      │                  │ Stripe webhook    │                        │
│      │                  │ secret            │                        │
│      │                  │                   │                        │
│      │ WebSocket:       │                   │                        │
│      │ "Order Confirmed"│                   │                        │
│      │◄─────────────────│                   │                        │
│      │                  │                   │                        │
│  SECURITY MEASURES:                                                  │
│  ✓ Server-side amount calculation (never trust client amounts)       │
│  ✓ Webhook signature verification (prevent spoofed webhooks)         │
│  ✓ Idempotency keys on intent creation (prevent duplicate charges)   │
│  ✓ PaymentIntent metadata includes orderId (link payment to order)   │
│  ✓ Stripe publishable key only on client (secret key server-only)    │
│  ✓ Webhook endpoint excluded from JWT auth (uses Stripe signature)   │
│  ✓ Payment amount re-verified against order total before confirming  │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```
### 6.5 File Upload Security
```text
VALIDATION CHAIN:
  1. File type whitelist: image/jpeg, image/png, image/webp ONLY
  2. File size limit: 5MB max per file, 25MB max per request
  3. File count limit: Max 8 images per product
  4. Magic bytes verification: Check actual file header, not just extension
  5. Image dimension limits: Min 200x200, Max 4096x4096
  6. Filename sanitization: Strip special characters, generate UUID-based name
  7. Cloudinary/S3 upload with server-side credentials (never exposed to client)
  8. Returned URL is a CDN URL (not a direct storage URL)
  9. Only SELLER role can upload product images
  10. Only authenticated users can upload avatars (to their own profile)
```
## 7. 🔔 Real-Time Design
### 7.1 WebSocket Architecture
```text
┌────────────────────────────────────────────────────────────────┐
│                    WEBSOCKET DESIGN                             │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  PROTOCOL: STOMP over SockJS                                   │
│  ENDPOINT: /ws                                                 │
│  AUTH: JWT token passed in STOMP CONNECT headers               │
│                                                                │
│  CONNECTION FLOW:                                              │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ 1. Client initiates SockJS connection to /ws            │   │
│  │ 2. STOMP CONNECT frame includes:                        │   │
│  │    { Authorization: "Bearer <accessToken>" }            │   │
│  │ 3. Server ChannelInterceptor validates JWT              │   │
│  │ 4. On success: CONNECTED frame returned                 │   │
│  │ 5. On failure: Connection rejected                      │   │
│  │ 6. Client subscribes to /user/queue/notifications       │   │
│  │ 7. Server maps userId → WebSocket session               │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                │
│  SUBSCRIPTION CHANNELS:                                        │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                                                         │   │
│  │  /user/queue/notifications                              │   │
│  │    → Personal notifications (per-user queue)            │   │
│  │    → Used by BOTH Customer and Seller                   │   │
│  │                                                         │   │
│  │  /topic/products/stock-updates   (OPTIONAL/FUTURE)      │   │
│  │    → Broadcast stock changes to all connected clients   │   │
│  │    → "Only 2 left!" type indicators                     │   │
│  │                                                         │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                │
│  RECONNECTION STRATEGY (Client-side):                          │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ 1. SockJS handles transport fallback (WebSocket →       │   │
│  │    xhr-streaming → xhr-polling)                         │   │
│  │ 2. On disconnect: Exponential backoff retry             │   │
│  │    (1s → 2s → 4s → 8s → max 30s)                       │   │
│  │ 3. On reconnect: Re-subscribe + fetch missed            │   │
│  │    notifications via REST API                           │   │
│  │ 4. On token expiry during WS session: Client refreshes  │   │
│  │    token via REST, then reconnects WS with new token    │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```
### 7.2 Notification Events & Triggers
```text
┌──────────────────────────────────────────────────────────────────────┐
│                    NOTIFICATION EVENT CATALOG                         │
├──────────────────┬───────────────┬──────────────┬────────────────────┤
│  EVENT TYPE      │  RECIPIENT    │  TRIGGER     │  CHANNELS          │
├──────────────────┼───────────────┼──────────────┼────────────────────┤
│ ORDER_PLACED     │ Seller        │ New order    │ WebSocket + Email  │
│                  │               │ created      │                    │
├──────────────────┼───────────────┼──────────────┼────────────────────┤
│ ORDER_CONFIRMED  │ Customer      │ Payment      │ WebSocket + Email  │
│                  │               │ succeeded    │                    │
├──────────────────┼───────────────┼──────────────┼────────────────────┤
│ ORDER_SHIPPED    │ Customer      │ Seller marks │ WebSocket + Email  │
│                  │               │ as shipped   │                    │
├──────────────────┼───────────────┼──────────────┼────────────────────┤
│ ORDER_DELIVERED  │ Customer      │ Seller marks │ WebSocket + Email  │
│                  │               │ as delivered │                    │
├──────────────────┼───────────────┼──────────────┼────────────────────┤
│ ORDER_CANCELLED  │ Customer      │ Cancellation │ WebSocket + Email  │
│                  │               │ processed    │                    │
├──────────────────┼───────────────┼──────────────┼────────────────────┤
│ PAYMENT_FAILED   │ Customer      │ Stripe       │ WebSocket + Email  │
│                  │               │ webhook      │                    │
├──────────────────┼───────────────┼──────────────┼────────────────────┤
│ REFUND_PROCESSED │ Customer      │ Stripe       │ WebSocket + Email  │
│                  │               │ refund event │                    │
├──────────────────┼───────────────┼──────────────┼────────────────────┤
│ NEW_REVIEW       │ Seller        │ Customer     │ WebSocket only     │
│                  │               │ posts review │                    │
├──────────────────┼───────────────┼──────────────┼────────────────────┤
│ LOW_STOCK        │ Seller        │ Stock drops  │ WebSocket + Email  │
│                  │               │ below        │                    │
│                  │               │ threshold    │                    │
├──────────────────┼───────────────┼──────────────┼────────────────────┤
│ OUT_OF_STOCK     │ Seller        │ Stock        │ WebSocket + Email  │
│                  │               │ reaches 0    │                    │
├──────────────────┼───────────────┼──────────────┼────────────────────┤
│ WELCOME          │ Customer      │ Registration │ Email only         │
│                  │               │ completed    │                    │
├──────────────────┼───────────────┼──────────────┼────────────────────┤
│ PASSWORD_RESET   │ Any user      │ Reset        │ Email only         │
│                  │               │ requested    │                    │
└──────────────────┴───────────────┴──────────────┴────────────────────┘
```
### 7.3 Notification Payload Structure
```text
WebSocket message payload (JSON sent to client):
{
  "id": "notification-uuid",
  "type": "ORDER_SHIPPED",
  "title": {
    "en": "Order Shipped!",
    "ar": "تم شحن الطلب!"
  },
  "message": {
    "en": "Your order #STR-20250115-0042 has been shipped.",
    "ar": "تم شحن طلبك رقم #STR-20250115-0042."
  },
  "data": {
    "orderId": "order-uuid",
    "orderNumber": "STR-20250115-0042"
  },
  "timestamp": "2025-01-15T14:30:00Z",
  "isRead": false
}

Client-side handles:
  1. Display toast notification (using title + message in current language)
  2. Update notification badge counter
  3. If on relevant page (e.g., order details), auto-refresh data
  4. Store in local notification list
```
## 8. 📊 Seller Dashboard & Analytics
### 8.1 Dashboard Overview Layout
```text
┌──────────────────────────────────────────────────────────────┐
│                    SELLER DASHBOARD                           │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────┐ ┌─────────────┐ ┌────────────┐ ┌────────┐ │
│  │ Total       │ │ Orders      │ │ Revenue    │ │ Avg    │ │
│  │ Revenue     │ │ Today       │ │ This Month │ │ Order  │ │
│  │ (SAR)       │ │             │ │            │ │ Value  │ │
│  │  ▲ 12%      │ │  ▲ 5       │ │  ▲ 8%     │ │ 245SAR │ │
│  └─────────────┘ └─────────────┘ └────────────┘ └────────┘ │
│                                                              │
│  ┌────────────────────────────────┐ ┌────────────────────┐  │
│  │  Revenue Chart (Line/Bar)      │ │ Orders by Status   │  │
│  │  Daily / Weekly / Monthly      │ │ (Doughnut Chart)   │  │
│  │  ──────────────────────────    │ │    ┌───┐           │  │
│  │  │     ╱\    ╱\               │ │    │   │ Pending   │  │
│  │  │    ╱  \  ╱  \              │ │    │   │ Confirmed │  │
│  │  │   ╱    \╱    \             │ │    │   │ Shipped   │  │
│  │  │  ╱            \            │ │    │   │ Delivered │  │
│  │  └──────────────────────────  │ │    └───┘           │  │
│  └────────────────────────────────┘ └────────────────────┘  │
│                                                              │
│  ┌────────────────────────────────┐ ┌────────────────────┐  │
│  │  Top Selling Products          │ │ Inventory Alerts   │  │
│  │  ┌────┬──────────┬──────────┐  │ │ ⚠ T-Shirt L/Red   │  │
│  │  │ #  │ Product  │ Sold     │  │ │   Stock: 3         │  │
│  │  ├────┼──────────┼──────────┤  │ │ 🔴 Jeans M/Blue   │  │
│  │  │ 1  │ T-Shirt  │ 145      │  │ │   Stock: 0         │  │
│  │  │ 2  │ Jeans    │ 98       │  │ │ ⚠ Hoodie XL/Black │  │
│  │  │ 3  │ Hoodie   │ 76       │  │ │   Stock: 2         │  │
│  │  └────┴──────────┴──────────┘  │ └────────────────────┘  │
│  └────────────────────────────────┘                          │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐   │
│  │  Recent Orders                                        │   │
│  │  ┌──────────┬──────────┬────────┬──────────┬────────┐ │   │
│  │  │ Order #  │ Customer │ Total  │ Status   │ Date   │ │   │
│  │  ├──────────┼──────────┼────────┼──────────┼────────┤ │   │
│  │  │ STR-042  │ Ahmed    │ 350SAR │ Pending  │ Today  │ │   │
│  │  │ STR-041  │ Sara     │ 520SAR │ Shipped  │ Yest.  │ │   │
│  │  └──────────┴──────────┴────────┴──────────┴────────┘ │   │
│  └───────────────────────────────────────────────────────┘   │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```
### 8.2 Analytics Metrics Tracked
```text
┌────────────────────────────────────────────────────────────────┐
│                    METRICS CATALOG                              │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  REVENUE METRICS:                                              │
│  ├── Total revenue (by period: today/week/month/year/custom)   │
│  ├── Revenue trend (daily data points for chart)               │
│  ├── Revenue comparison (this period vs previous period)       │
│  ├── Revenue by category                                       │
│  └── Revenue by product                                        │
│                                                                │
│  ORDER METRICS:                                                │
│  ├── Total order count (by period)                             │
│  ├── Orders by status (pie/doughnut chart data)                │
│  ├── Average order value                                       │
│  ├── Order completion rate (delivered / total)                  │
│  ├── Cancellation rate                                         │
│  └── Orders trend (daily data points for chart)                │
│                                                                │
│  PRODUCT METRICS:                                              │
│  ├── Top-selling products (by units sold and by revenue)       │
│  ├── Products with zero sales                                  │
│  ├── Most wishlisted products                                  │
│  ├── Most reviewed products                                    │
│  └── Average rating distribution                               │
│                                                                │
│  INVENTORY METRICS:                                            │
│  ├── Low-stock items (below threshold)                         │
│  ├── Out-of-stock items (quantity = 0)                         │
│  ├── Total inventory value (quantity × price)                  │
│  └── Inventory turnover indicators                             │
│                                                                │
│  CUSTOMER METRICS (Basic):                                     │
│  ├── New registrations (by period)                             │
│  ├── Total customers                                           │
│  └── Repeat customer rate                                      │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```
### 8.3 Chart Data Flow
```text
REAL-TIME DASHBOARD REQUEST:
┌────────────────────────────────────────────────────────────────┐
│                                                                │
│  1. Seller opens Dashboard page                                │
│  2. Angular calls GET /api/v1/seller/analytics/dashboard       │
│     ?period=MONTHLY&from=2025-01-01&to=2025-01-31             │
│                                                                │
│  3. Analytics Module:                                          │
│     a. Check Redis cache: analytics:dashboard:monthly          │
│     b. CACHE HIT → return cached data                          │
│     c. CACHE MISS:                                             │
│        i.  Check analytics_snapshots collection for            │
│            pre-computed data                                    │
│        ii. For current period (today/this week):               │
│            Run MongoDB aggregation pipeline on orders:          │
│            - $match: { createdAt: { $gte: from, $lte: to } }  │
│            - $group: { _id: { $dateToString: "date" },         │
│                        revenue: { $sum: "$totalAmount" },      │
│                        count: { $sum: 1 } }                    │
│            - $sort: { _id: 1 }                                 │
│        iii. Combine with pre-computed snapshots for             │
│             historical periods                                  │
│        iv.  Store result in Redis with TTL                      │
│     d. Return aggregated data                                  │
│                                                                │
│  4. Response format (Chart.js ready):                          │
│     {                                                          │
│       summary: {                                               │
│         totalRevenue: 45000,                                   │
│         orderCount: 187,                                       │
│         averageOrderValue: 240.6,                              │
│         revenueChange: +12.5,  ← % vs previous period         │
│         orderCountChange: +8.2                                 │
│       },                                                       │
│       revenueChart: {                                          │
│         labels: ["Jan 1", "Jan 2", ...],                       │
│         data: [1500, 2300, 1800, ...]                          │
│       },                                                       │
│       ordersByStatus: {                                        │
│         labels: ["Pending","Confirmed","Shipped","Delivered"], │
│         data: [12, 25, 38, 112]                                │
│       },                                                       │
│       topProducts: [...],                                      │
│       inventoryAlerts: [...]                                   │
│     }                                                          │
│                                                                │
│  SCHEDULED PRE-COMPUTATION:                                    │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ Daily at 02:00 AM (Spring @Scheduled):                   │  │
│  │  1. Aggregate previous day's orders                      │  │
│  │  2. Create DAILY snapshot document                       │  │
│  │  3. Every Monday: Create WEEKLY snapshot from daily data │  │
│  │  4. 1st of month: Create MONTHLY snapshot                │  │
│  │  5. Invalidate analytics cache in Redis                  │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```
### 8.4 Inventory Health Dashboard
```text
GET /api/v1/seller/analytics/inventory-health

Response:
{
  totalProducts: 85,
  totalVariants: 340,
  
  healthBreakdown: {
    healthy: 280,        ← stock > threshold
    lowStock: 42,        ← 0 < stock ≤ threshold
    outOfStock: 18       ← stock = 0
  },
  
  lowStockItems: [
    {
      productId, productName: { en, ar },
      variantSku, size: "L", color: "Red",
      currentStock: 3,
      threshold: 5,
      dailyAvgSales: 1.2   ← estimated days until out-of-stock
    },
    ...
  ],
  
  outOfStockItems: [
    {
      productId, productName: { en, ar },
      variantSku, size: "M", color: "Blue",
      lastSoldDate: "2025-01-10",
      wishlistCount: 15    ← demand indicator
    },
    ...
  ],
  
  totalInventoryValue: 125000  ← sum(stock × price)
}
```
## 9. 🌍 i18n & UX Design
### 9.1 Internationalization Architecture
```text
┌────────────────────────────────────────────────────────────────┐
│                   i18n ARCHITECTURE                             │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  TWO LAYERS OF LOCALIZATION:                                   │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  LAYER 1: STATIC UI TEXT (Frontend-owned)                │  │
│  │                                                          │  │
│  │  Managed by Angular's i18n or @ngx-translate             │  │
│  │                                                          │  │
│  │  /assets/i18n/en.json                                    │  │
│  │  {                                                       │  │
│  │    "nav.home": "Home",                                   │  │
│  │    "nav.products": "Products",                           │  │
│  │    "nav.cart": "Cart",                                   │  │
│  │    "product.addToCart": "Add to Cart",                   │  │
│  │    "product.outOfStock": "Out of Stock",                 │  │
│  │    "order.status.PENDING": "Pending",                    │  │
│  │    "order.status.SHIPPED": "Shipped",                    │  │
│  │    "checkout.total": "Total",                            │  │
│  │    "seller.dashboard": "Dashboard",                      │  │
│  │    ...                                                   │  │
│  │  }                                                       │  │
│  │                                                          │  │
│  │  /assets/i18n/ar.json                                    │  │
│  │  {                                                       │  │
│  │    "nav.home": "الرئيسية",                               │  │
│  │    "nav.products": "المنتجات",                           │  │
│  │    "nav.cart": "السلة",                                  │  │
│  │    "product.addToCart": "أضف إلى السلة",                 │  │
│  │    "product.outOfStock": "نفدت الكمية",                  │  │
│  │    "order.status.PENDING": "قيد الانتظار",               │  │
│  │    "order.status.SHIPPED": "تم الشحن",                   │  │
│  │    "checkout.total": "المجموع",                          │  │
│  │    "seller.dashboard": "لوحة التحكم",                    │  │
│  │    ...                                                   │  │
│  │  }                                                       │  │
│  │                                                          │  │
│  │  Language stored in: localStorage + user preferences     │  │
│  │  Default: Arabic (primary market)                        │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  LAYER 2: DYNAMIC CONTENT (Backend-owned)                │  │
│  │                                                          │  │
│  │  Product names, descriptions, category names,            │  │
│  │  notification messages — stored as bilingual objects      │  │
│  │                                                          │  │
│  │  MongoDB document:                                       │  │
│  │  {                                                       │  │
│  │    name: { en: "Classic T-Shirt", ar: "تيشيرت كلاسيكي" }│  │
│  │    description: { en: "...", ar: "..." }                 │  │
│  │  }                                                       │  │
│  │                                                          │  │
│  │  API STRATEGY:                                           │  │
│  │  Option A (chosen): Return BOTH languages always         │  │
│  │    → Client picks the right one based on current lang    │  │
│  │    → Simpler backend, no query param needed              │  │
│  │    → Slightly larger payload, but cacheable              │  │
│  │                                                          │  │
│  │  Client rendering:                                       │  │
│  │    {{ product.name[currentLang] }}                       │  │
│  │                                                          │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                │
│  SELLER INPUT:                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  When creating/editing products, Seller fills BOTH:      │  │
│  │                                                          │  │
│  │  ┌─────────────────┐  ┌─────────────────┐               │  │
│  │  │ English Name    │  │ Arabic Name     │               │  │
│  │  │ [Classic Shirt ]│  │ [تيشيرت كلاسيكي]│               │  │
│  │  └─────────────────┘  └─────────────────┘               │  │
│  │                                                          │  │
│  │  Validation: At least ONE language required               │  │
│  │  Best practice: Both encouraged via UI hints              │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```
### 9.2 RTL Support Strategy
```text
┌────────────────────────────────────────────────────────────────┐
│                    RTL SUPPORT                                  │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  APPROACH: CSS Logical Properties + dir attribute              │
│                                                                │
│  1. HTML ROOT:                                                 │
│     <html [dir]="currentLang === 'ar' ? 'rtl' : 'ltr'"        │
│           [lang]="currentLang">                                │
│                                                                │
│  2. CSS STRATEGY:                                              │
│     ✅ Use: margin-inline-start, padding-inline-end,           │
│            border-inline-start, inset-inline-start             │
│     ❌ Avoid: margin-left, padding-right (fixed physical)      │
│                                                                │
│     ✅ Use: flexbox (direction auto-flips with dir="rtl")      │
│     ✅ Use: CSS Grid (inherits direction)                      │
│                                                                │
│  3. FONT STRATEGY:                                             │
│     Arabic: 'Cairo', 'Tajawal', or 'IBM Plex Sans Arabic'     │
│     English: 'Inter', 'Poppins', or system font stack          │
│     Applied via CSS:                                           │
│     :host-context([lang="ar"]) { font-family: 'Cairo'; }      │
│     :host-context([lang="en"]) { font-family: 'Inter'; }      │
│                                                                │
│  4. ICONS & ARROWS:                                            │
│     Directional icons (arrows, chevrons) flip via CSS:         │
│     [dir="rtl"] .icon-arrow { transform: scaleX(-1); }        │
│                                                                │
│  5. COMPONENT-LEVEL:                                           │
│     Each Angular component designed to be dir-agnostic         │
│     using logical properties. No RTL-specific CSS files.       │
│                                                                │
│  6. CHARTS (Chart.js):                                         │
│     Chart.js is inherently LTR. For RTL:                       │
│     - Reverse data arrays for bar charts                       │
│     - Position legend on the right for RTL                     │
│     - Mirror tooltip positioning                               │
│                                                                │
│  7. FORM INPUTS:                                               │
│     Input text direction follows the dir attribute              │
│     Numeric inputs: Always LTR (phone, price, quantities)      │
│     Applied via: input[type="number"] { direction: ltr; }      │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```
### 9.3 Dark/Light Theme Strategy
```text
┌────────────────────────────────────────────────────────────────┐
│                  THEME SYSTEM                                   │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  APPROACH: CSS Custom Properties (Variables)                   │
│                                                                │
│  :root (Light - default) {                                     │
│    --bg-primary: #FFFFFF;                                      │
│    --bg-secondary: #F5F5F5;                                    │
│    --text-primary: #1A1A1A;                                    │
│    --text-secondary: #6B7280;                                  │
│    --accent: #C8A97E;            ← Sutra brand gold            │
│    --accent-hover: #B8955E;                                    │
│    --border: #E5E7EB;                                          │
│    --card-bg: #FFFFFF;                                         │
│    --card-shadow: 0 1px 3px rgba(0,0,0,0.1);                  │
│    --success: #10B981;                                         │
│    --error: #EF4444;                                           │
│    --warning: #F59E0B;                                         │
│  }                                                             │
│                                                                │
│  [data-theme="dark"] {                                         │
│    --bg-primary: #0F0F0F;                                      │
│    --bg-secondary: #1A1A1A;                                    │
│    --text-primary: #F5F5F5;                                    │
│    --text-secondary: #9CA3AF;                                  │
│    --accent: #D4AF37;             ← Slightly brighter gold     │
│    --accent-hover: #E5C04B;                                    │
│    --border: #2D2D2D;                                          │
│    --card-bg: #1F1F1F;                                         │
│    --card-shadow: 0 1px 3px rgba(0,0,0,0.4);                  │
│    --success: #34D399;                                         │
│    --error: #F87171;                                           │
│    --warning: #FBBF24;                                         │
│  }                                                             │
│                                                                │
│  TOGGLE LOGIC:                                                 │
│  1. Check user preference (from profile or localStorage)       │
│  2. Fallback to system preference: prefers-color-scheme        │
│  3. On toggle: Update data-theme attribute on <html>           │
│  4. Persist to localStorage immediately                        │
│  5. Sync to backend profile preferences (debounced)            │
│                                                                │
│  PRIORITY: localStorage > profilePreference > systemPreference │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```
## 10. 🧠 Design Decisions & Trade-offs
### Decision 1: Monolith over Microservices
```text
┌────────────────────────────────────────────────────────────────┐
│  WHY MONOLITH:                                                 │
│                                                                │
│  ✅ REASONS FOR:                                               │
│  1. TEAM SIZE: Solo developer or small team (1-3).             │
│     Microservices need dedicated DevOps, which we don't have.  │
│                                                                │
│  2. DOMAIN COMPLEXITY: Single-brand clothing store is NOT      │
│     complex enough to justify distributed architecture.        │
│     No multi-tenant, no vendor isolation, no independent       │
│     scaling needs per domain.                                  │
│                                                                │
│  3. OPERATIONAL SIMPLICITY: One artifact to deploy, one        │
│     process to monitor, one log stream, no service discovery,  │
│     no distributed tracing, no circuit breakers.               │
│                                                                │
│  4. TRANSACTION SIMPLICITY: Order creation touches cart,       │
│     inventory, payment, notifications — in a monolith this is  │
│     one method call chain. In microservices this becomes        │
│     sagas, compensating transactions, eventual consistency.    │
│                                                                │
│  5. DEVELOPMENT SPEED: No inter-service contracts, no API      │
│     versioning between services, no network latency for        │
│     internal calls. Features ship faster.                      │
│                                                                │
│  6. COST: One server/container vs 5-13 services × instances    │
│     × monitoring × logging infrastructure.                     │
│                                                                │
│  ❌ ACKNOWLEDGED TRADE-OFFS:                                   │
│  - Cannot scale modules independently (e.g., scale only        │
│    catalog reads). Mitigated by Redis caching.                 │
│  - One module's memory leak affects everything. Mitigated by   │
│    good testing and monitoring.                                │
│  - Tech stack locked to Java/Spring for all modules. Acceptable│
│    since Spring Boot is capable for all our needs.             │
│                                                                │
│  🔮 FUTURE-PROOFING:                                           │
│  The service-oriented module structure means we CAN extract    │
│  modules into microservices later if/when:                     │
│  - Traffic exceeds single-server capacity                      │
│  - Team grows to 5+ developers working on different domains    │
│  - Business becomes a marketplace (multi-vendor)               │
│  The module interfaces become the service API contracts.       │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```
### Decision 2: MongoDB over SQL (PostgreSQL/MySQL)
```text
┌────────────────────────────────────────────────────────────────┐
│  WHY MONGODB:                                                  │
│                                                                │
│  ✅ REASONS FOR:                                               │
│  1. FLEXIBLE PRODUCT SCHEMA: Clothing products have variable   │
│     attributes (sizes differ by category: S/M/L for shirts,    │
│     28/30/32 for jeans). Document model handles this naturally │
│     without EAV patterns or JSON columns.                      │
│                                                                │
│  2. EMBEDDED DOCUMENTS: Orders snapshot product details,       │
│     addresses, price at time of purchase. Documents naturally  │
│     contain these snapshots. In SQL, this requires multiple    │
│     snapshot tables or JSON columns.                           │
│                                                                │
│  3. BILINGUAL CONTENT: { en: "...", ar: "..." } is native     │
│     in documents. In SQL, you need separate columns or a       │
│     translations table with joins.                             │
│                                                                │
│  4. VARIANT ARRAYS: Products have variants (size × color       │
│     combinations). Stored as embedded array in the product     │
│     document. In SQL: separate variants table + joins.         │
│                                                                │
│  5. AGGREGATION FRAMEWORK: Powerful for analytics queries      │
│     ($group, $match, $project, $dateToString, $facet).         │
│     Can handle our analytics needs without a separate          │
│     data warehouse.                                            │
│                                                                │
│  6. ATLAS SEARCH: If we need full-text search later,           │
│     MongoDB Atlas Search is built-in. No separate              │
│     Elasticsearch cluster needed.                              │
│                                                                │
│  ❌ ACKNOWLEDGED TRADE-OFFS:                                   │
│  - No multi-document ACID transactions for complex flows.      │
│    Mitigated by: MongoDB 4.0+ supports multi-document          │
│    transactions. We use them for order creation.               │
│  - No JOIN capability. Mitigated by: intentional               │
│    denormalization + application-level joins (service layer).  │
│  - Less mature tooling for migrations compared to Flyway/      │
│    Liquibase. Mitigated by: MongoDB schema is flexible, and    │
│    we use Mongock for migration scripts.                       │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```
### Decision 3: Redis — Why and Where
```text
┌────────────────────────────────────────────────────────────────┐
│  WHY REDIS:                                                    │
│                                                                │
│  ✅ REASONS FOR:                                               │
│  1. CART PERFORMANCE: Carts are modified constantly (add,      │
│     remove, update quantity). Sub-millisecond Redis operations  │
│     prevent MongoDB from being hammered with frequent writes.  │
│                                                                │
│  2. SESSION/TOKEN MANAGEMENT: JWT blacklist checks happen on   │
│     EVERY request. Must be < 1ms. Redis SET membership check   │
│     is O(1). MongoDB query would add 2-5ms per request.       │
│                                                                │
│  3. RATE LIMITING: Needs atomic increment + TTL per key.       │
│     Redis INCR + EXPIRE is purpose-built for this.             │
│                                                                │
│  4. CACHE LAYER: Product data is read 100x more than written.  │
│     Redis cache reduces MongoDB read load by ~80%.             │
│                                                                │
│  5. COST-EFFECTIVE: Single Redis instance (or small cluster)   │
│     handles all above use cases. No separate cache server,     │
│     session store, or rate limiter needed.                     │
│                                                                │
│  ❌ ACKNOWLEDGED TRADE-OFFS:                                   │
│  - Data loss risk (Redis is in-memory). Mitigated by:          │
│    * Cart: async backup to MongoDB                             │
│    * Cache: just a cache — rebuild from MongoDB on miss        │
│    * Blacklist: worst case a logged-out token works for 15min  │
│  - Additional infrastructure. Mitigated by: Redis is trivial   │
│    to operate (single process), or use managed Redis.          │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```
### Decision 4: Additional Design Choices
```text
┌────────────────────────────────────────────────────────────────┐
│  OTHER KEY DECISIONS:                                          │
│                                                                │
│  ANGULAR OVER REACT/VUE:                                       │
│  → Given constraint. Angular's opinionated structure fits      │
│    well for a project with clear module boundaries (customer   │
│    vs seller), built-in i18n support, and typed services.      │
│                                                                │
│  SINGLE SPA FOR BOTH ROLES (vs separate apps):                 │
│  → Less deployment complexity. Route guards handle separation. │
│  → Shared components (product cards, notification system).     │
│  → Lazy loading ensures seller dashboard code isn't loaded     │
│    for customers.                                              │
│                                                                │
│  CLOUDINARY OVER S3:                                           │
│  → Built-in image transformations (resize, crop, format).     │
│  → Automatic WebP conversion for performance.                  │
│  → CDN included. S3 would need CloudFront setup.              │
│  → Trade-off: Vendor lock-in, but File Module abstracts it.   │
│                                                                │
│  STOMP/SOCKJS OVER RAW WEBSOCKET:                              │
│  → STOMP provides topic/queue semantics (no custom protocol). │
│  → SockJS provides fallback for browsers without WebSocket.    │
│  → Spring has first-class STOMP support.                       │
│                                                                │
│  CHART.JS OVER D3/ECHARTS:                                     │
│  → Simple, lightweight, covers all needed chart types.         │
│  → No need for complex custom visualizations.                  │
│  → Trade-off: Less customizable than D3.                       │
│                                                                │
│  SAR (SAUDI RIYAL) AS DEFAULT CURRENCY:                        │
│  → Single-brand, single-market assumption. No currency         │
│    conversion needed. Stored as integer (halalas) to avoid     │
│    floating point issues. Display as SAR with 2 decimals.      │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```
Let me continue from the Design Decisions closing and then provide the detailed week-by-week roadmap.

🧥 Sutra (سُترة) — Complete System Architecture & Execution Blueprint (Continued)
## 10. 🧠 Design Decisions & Trade-offs (Continued)
### Decision 4: Additional Design Choices (Continued)
```text
┌────────────────────────────────────────────────────────────────┐
│  OTHER KEY DECISIONS (continued):                              │
│                                                                │
│  PRICE STORAGE AS INTEGER (HALALAS):                           │
│  → 150.75 SAR stored as 15075 (integer)                       │
│  → Eliminates floating-point arithmetic errors               │
│  → All calculations done in halalas server-side               │
│  → Frontend formats: value / 100 + " SAR"                    │
│  → Stripe also expects amounts in smallest currency unit      │
│                                                                │
│  API VERSIONING (/api/v1/):                                    │
│  → URL-based versioning for simplicity                        │
│  → Allows future /api/v2/ without breaking mobile clients     │
│  → Trade-off: URL pollution, but manageable for monolith      │
│                                                                │
│  ORDER NUMBER FORMAT (STR-YYYYMMDD-NNNN):                     │
│  → Human-readable for customer support                        │
│  → Date-embedded for quick visual reference                   │
│  → Sequential counter per day (atomic increment in MongoDB)   │
│  → Internal _id remains MongoDB ObjectId for DB operations    │
│                                                                │
│  GUEST CART vs AUTH-ONLY CART:                                  │
│  → Decision: Support guest cart (stored in Redis by sessionId)│
│  → On login: Merge guest cart into user cart                  │
│  → Reasoning: Reduces friction for browsing customers         │
│  → Trade-off: More complex cart logic (merge conflicts)       │
│                                                                │
│  SELLER ACCOUNT BOOTSTRAPPING:                                 │
│  → First-user-is-owner pattern OR environment variable seed   │
│  → On first application start: if no SELLER exists in DB,     │
│    create one from environment variables (email, password)    │
│  → No public seller registration endpoint                     │
│  → Reasoning: Single-brand = single seller, permanently       │
│                                                                │
│  IMAGE OPTIMIZATION STRATEGY:                                  │
│  → Cloudinary transformations on-the-fly:                     │
│    * Thumbnail: w_300,h_300,c_fill,f_auto,q_auto              │
│    * Product page: w_800,h_800,c_fit,f_auto,q_auto            │
│    * Zoom: w_1200,h_1200,c_fit,f_auto,q_auto                  │
│  → f_auto serves WebP to supporting browsers                  │
│  → q_auto adjusts quality based on content                    │
│  → Store base URL; generate transformation URLs dynamically    │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```
### Decision 5: What Was Intentionally Left Out (and Why)
```text
┌────────────────────────────────────────────────────────────────┐
│  INTENTIONAL EXCLUSIONS:                                       │
│                                                                │
│  ❌ ELASTICSEARCH:                                              │
│  → Overkill for a single-brand catalog (likely < 500 products)│
│  → MongoDB text indexes + Atlas Search cover our needs        │
│  → Search Module abstracts this — can add ES later if needed  │
│                                                                │
│  ❌ MESSAGE QUEUE (RabbitMQ/Kafka):                             │
│  → In-monolith method calls are synchronous and reliable      │
│  → Spring @Async for non-critical paths (emails, analytics)   │
│  → No distributed system = no need for message broker         │
│  → If we extract services later, add queues at that point     │
│                                                                │
│  ❌ GRAPHQL:                                                    │
│  → REST is simpler, well-understood, sufficient               │
│  → No complex nested data fetching requirements               │
│  → Angular services map naturally to REST endpoints           │
│                                                                │
│  ❌ MULTI-CURRENCY:                                             │
│  → Single market (Saudi Arabia), single currency (SAR)        │
│  → Adding currency conversion adds complexity with no value   │
│  → Can be added later if international expansion happens      │
│                                                                │
│  ❌ COUPON/DISCOUNT ENGINE:                                     │
│  → Excluded from MVP scope                                    │
│  → Can be added as a new module post-launch                   │
│  → compareAtPrice field on products handles simple "was/now"  │
│    pricing for now                                            │
│                                                                │
│  ❌ SHIPPING PROVIDER INTEGRATION:                              │
│  → MVP: Seller manually updates order status                  │
│  → No Aramex/SMSA API integration yet                        │
│  → Order status flow is designed to accommodate it later       │
│                                                                │
│  ❌ MULTI-LANGUAGE BEYOND AR/EN:                                │
│  → Bilingual object structure { en, ar } supports only two    │
│  → If more languages needed, migrate to Map<String,String>    │
│  → Unlikely for a Saudi brand in near term                    │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```
## 11. 🗓️ Execution Plan — Week-by-Week Roadmap
### Overview Timeline
```text
┌────────────────────────────────────────────────────────────────────┐
│                    10-WEEK EXECUTION PLAN                          │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  Phase 1: FOUNDATION        (Weeks 1-2)   █████░░░░░░░░░░░░░░░   │
│  Phase 2: CORE COMMERCE     (Weeks 3-5)   ░░░░░████████░░░░░░░   │
│  Phase 3: TRANSACTIONS      (Weeks 6-7)   ░░░░░░░░░░░░░████░░░   │
│  Phase 4: ENGAGEMENT        (Week 8)      ░░░░░░░░░░░░░░░░██░░   │
│  Phase 5: SELLER DASHBOARD  (Week 9)      ░░░░░░░░░░░░░░░░░░██   │
│  Phase 6: POLISH & LAUNCH   (Week 10)     ░░░░░░░░░░░░░░░░░░░██  │
│                                                                    │
│  MVP READY: End of Week 7                                          │
│  FULL FEATURE: End of Week 10                                      │
│                                                                    │
│  DEPENDENCY CHAIN:                                                 │
│  Auth → Profile → Catalog → Inventory → Cart → Order → Payment    │
│                    ↓                              ↓                │
│                 Wishlist                      Analytics             │
│                 Reviews                      Notifications         │
│                 Search                                             │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```
### 📅 Week 1 — Foundation & Project Setup
```text
┌────────────────────────────────────────────────────────────────────┐
│  WEEK 1: FOUNDATION & PROJECT SETUP                                │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  🎯 GOALS:                                                        │
│  • Establish both project scaffolds with proper structure          │
│  • Set up all external service connections                        │
│  • Implement authentication (auth-module) end-to-end              │
│  • Create the Angular shell with routing, theming, and i18n       │
│                                                                    │
│  ⚙️ BACKEND FOCUS:                                                │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  PROJECT STRUCTURE:                                          │  │
│  │  • Spring Boot project initialization                       │  │
│  │  • Package structure reflecting module boundaries:           │  │
│  │    com.sutra.auth / com.sutra.catalog / com.sutra.order...  │  │
│  │  • Each package: controller / service / repository / dto /  │  │
│  │    model subpackages                                        │  │
│  │  • Global exception handler (centralized error responses)   │  │
│  │  • Request/Response logging interceptor                     │  │
│  │  • CORS configuration                                      │  │
│  │                                                              │  │
│  │  CONNECTIONS:                                                │  │
│  │  • MongoDB connection + database initialization              │  │
│  │  • Redis connection + configuration                          │  │
│  │  • Cloudinary SDK configuration                              │  │
│  │  • Environment-based configuration (dev/staging/prod)        │  │
│  │                                                              │  │
│  │  AUTH MODULE (COMPLETE):                                     │  │
│  │  • User model + users collection                            │  │
│  │  • Registration endpoint (Customer)                         │  │
│  │  • Login endpoint (JWT issuance)                            │  │
│  │  • JWT filter chain (validation + SecurityContext)           │  │
│  │  • Refresh token mechanism                                  │  │
│  │  • Logout + token blacklist (Redis)                         │  │
│  │  • Password hashing (BCrypt)                                │  │
│  │  • Role-based access configuration                          │  │
│  │  • Seller account seeding from environment variables        │  │
│  │                                                              │  │
│  │  SECURITY FILTER CHAIN:                                     │  │
│  │  • CORS filter                                              │  │
│  │  • JWT authentication filter                                │  │
│  │  • Role authorization rules                                 │  │
│  │  • Public endpoint whitelist                                │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🎨 FRONTEND FOCUS:                                               │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  PROJECT STRUCTURE:                                          │  │
│  │  • Angular project initialization                           │  │
│  │  • Feature module structure:                                │  │
│  │    /auth, /customer, /seller, /shared                       │  │
│  │  • Lazy-loaded route modules defined                        │  │
│  │  • Core services scaffold (API service, auth service,       │  │
│  │    storage service)                                         │  │
│  │                                                              │  │
│  │  THEMING:                                                   │  │
│  │  • CSS custom properties for dark/light theme               │  │
│  │  • Theme toggle component                                   │  │
│  │  • Theme persistence (localStorage)                         │  │
│  │  • Global style variables defined                           │  │
│  │                                                              │  │
│  │  i18n SETUP:                                                │  │
│  │  • Translation library installed and configured             │  │
│  │  • en.json / ar.json skeleton files                         │  │
│  │  • Language toggle component                                │  │
│  │  • RTL/LTR switching on dir attribute                       │  │
│  │  • Font loading for both languages                          │  │
│  │                                                              │  │
│  │  SHELL LAYOUT:                                              │  │
│  │  • Responsive navigation bar (mobile hamburger menu)        │  │
│  │  • Footer component                                         │  │
│  │  • Main layout with router outlet                           │  │
│  │  • Customer layout vs Seller layout (two layout shells)     │  │
│  │                                                              │  │
│  │  AUTH PAGES:                                                │  │
│  │  • Login page (form + validation + API integration)         │  │
│  │  • Registration page (form + validation + API integration)  │  │
│  │  • HTTP interceptor (JWT attachment + 401 handling)         │  │
│  │  • Auth guard (route protection)                            │  │
│  │  • Role guard (Customer vs Seller routes)                   │  │
│  │  • Token refresh interceptor logic                          │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🔧 DEVOPS:                                                       │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  • Git repository initialization (monorepo or two repos)    │  │
│  │  • .gitignore, .editorconfig, README                        │  │
│  │  • Docker Compose for local development:                    │  │
│  │    - MongoDB container                                      │  │
│  │    - Redis container                                        │  │
│  │    - Spring Boot (local run)                                │  │
│  │    - Angular dev server (local run)                         │  │
│  │  • Environment variable template (.env.example)             │  │
│  │  • API documentation setup (Swagger/OpenAPI config)         │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  📦 DELIVERABLES:                                                  │
│  ✅ Both projects scaffolded with proper structure                 │
│  ✅ MongoDB, Redis, Cloudinary connected                          │
│  ✅ Full authentication flow working (register → login → JWT)     │
│  ✅ Angular shell with theme toggle, language toggle, routing      │
│  ✅ Login and registration pages functional                       │
│  ✅ Protected routes working with role guards                     │
│  ✅ Docker Compose for local development                          │
│  ✅ Seller account seeded and loginable                           │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```
### 📅 Week 2 — User Profile & Catalog Foundation
```text
┌────────────────────────────────────────────────────────────────────┐
│  WEEK 2: USER PROFILE & CATALOG FOUNDATION                        │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  🎯 GOALS:                                                        │
│  • Complete profile management (addresses, preferences)           │
│  • Build the catalog module (products + categories CRUD)          │
│  • Implement file upload pipeline                                 │
│  • Seller can create and manage products                          │
│                                                                    │
│  ⚙️ BACKEND FOCUS:                                                │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  PROFILE MODULE:                                             │  │
│  │  • Profile model + profiles collection                      │  │
│  │  • Auto-create profile on registration                      │  │
│  │  • Get profile endpoint                                     │  │
│  │  • Update profile endpoint (name, phone, avatar)            │  │
│  │  • Address management (add, update, delete, set default)    │  │
│  │  • Preferences endpoint (language, theme)                   │  │
│  │  • Password change endpoint (auth module addition)          │  │
│  │  • Forgot/Reset password flow (auth module addition)        │  │
│  │                                                              │  │
│  │  FILE MODULE:                                                │  │
│  │  • Cloudinary integration service                           │  │
│  │  • Upload endpoint (multipart form data)                    │  │
│  │  • File validation (type, size, dimensions, magic bytes)    │  │
│  │  • Delete endpoint                                          │  │
│  │  • URL transformation helpers (thumbnail, medium, large)    │  │
│  │  • Upload size limits configuration                         │  │
│  │                                                              │  │
│  │  CATALOG MODULE:                                             │  │
│  │  • Product model + products collection                      │  │
│  │  • Category model + categories collection                   │  │
│  │  • Seller endpoints:                                        │  │
│  │    - CRUD for categories                                    │  │
│  │    - CRUD for products                                      │  │
│  │    - Product variant management                             │  │
│  │    - Product activation/deactivation                        │  │
│  │    - Image assignment to products                           │  │
│  │  • Public endpoints:                                        │  │
│  │    - List products (paginated)                              │  │
│  │    - Get product by ID                                      │  │
│  │    - List categories (tree structure)                       │  │
│  │    - Filter products (category, size, color, price range)   │  │
│  │  • MongoDB text index on product name + description         │  │
│  │  • Bilingual field handling ({ en, ar } in model)           │  │
│  │  • Product validation (required fields per language)        │  │
│  │                                                              │  │
│  │  SEARCH MODULE (Basic):                                     │  │
│  │  • Text search endpoint using MongoDB text index            │  │
│  │  • Search results with relevance scoring                    │  │
│  │  • Combined with filter parameters                          │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🎨 FRONTEND FOCUS:                                               │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  PROFILE PAGES (Customer):                                  │  │
│  │  • Profile view/edit page                                   │  │
│  │  • Avatar upload component                                  │  │
│  │  • Address management page (list, add, edit, delete)        │  │
│  │  • Address form component (reusable — used in checkout)     │  │
│  │  • Password change page                                     │  │
│  │  • Preferences section (language/theme — already working)   │  │
│  │                                                              │  │
│  │  SELLER — PRODUCT MANAGEMENT:                               │  │
│  │  • Seller sidebar navigation component                      │  │
│  │  • Seller dashboard layout shell                            │  │
│  │  • Product list page (table with search/filter/pagination)  │  │
│  │  • Product create/edit form:                                │  │
│  │    - Bilingual name and description inputs                  │  │
│  │    - Category selector (dropdown)                           │  │
│  │    - Price input                                            │  │
│  │    - Variant builder (size × color matrix)                  │  │
│  │    - Image upload with drag-and-drop + preview              │  │
│  │    - Image reordering                                       │  │
│  │    - Active/Featured toggles                                │  │
│  │  • Category management page (CRUD)                          │  │
│  │  • Image upload component (reusable)                        │  │
│  │                                                              │  │
│  │  CUSTOMER — PRODUCT BROWSING (Started):                     │  │
│  │  • Home page layout (hero, featured products, categories)   │  │
│  │  • Product card component (reusable)                        │  │
│  │  • Product grid/list view component                         │  │
│  │  • Category navigation component                            │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🔧 DEVOPS:                                                       │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  • Cloudinary account setup + API keys configured           │  │
│  │  • MongoDB indexes created (text indexes, compound indexes) │  │
│  │  • API documentation updated (Swagger annotations)          │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  📦 DELIVERABLES:                                                  │
│  ✅ Profile CRUD fully functional (including addresses)           │
│  ✅ File upload pipeline working (client → backend → Cloudinary)  │
│  ✅ Seller can create products with images, variants, bilingual   │
│  ✅ Categories CRUD working                                       │
│  ✅ Customer can browse products, see product details             │
│  ✅ Product filtering by category functional                      │
│  ✅ Seller dashboard shell with sidebar navigation                │
│  ✅ Forgot/Reset password flow working                            │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```
### 📅 Week 3 — Inventory & Advanced Product Browsing
```text
┌────────────────────────────────────────────────────────────────────┐
│  WEEK 3: INVENTORY & ADVANCED PRODUCT BROWSING                    │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  🎯 GOALS:                                                        │
│  • Build inventory management for Seller                          │
│  • Complete product browsing experience with all filters          │
│  • Implement product detail page with variant selection           │
│  • Set up Redis caching for catalog data                          │
│                                                                    │
│  ⚙️ BACKEND FOCUS:                                                │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  INVENTORY MODULE:                                           │  │
│  │  
text
┌────────────────────────────────────────────────────────────────────┐
│  WEEK 3: INVENTORY & ADVANCED PRODUCT BROWSING                    │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  🎯 GOALS:                                                        │
│  • Build inventory management for Seller                          │
│  • Complete product browsing experience with all filters          │
│  • Implement product detail page with variant selection           │
│  • Set up Redis caching for catalog data                          │
│                                                                    │
│  ⚙️ BACKEND FOCUS:                                                │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  INVENTORY MODULE (COMPLETE):                                │  │
│  │  • Inventory model + inventory collection                   │  │
│  │  • Auto-create inventory entries when product variants      │  │
│  │    are created (initial quantity: 0)                         │  │
│  │  • Seller endpoints:                                        │  │
│  │    - View inventory for a product (all variants)            │  │
│  │    - Update stock quantity (single variant)                 │  │
│  │    - Bulk update stock quantities (multiple variants)       │  │
│  │    - Set low-stock threshold per variant                    │  │
│  │    - View all low-stock items                               │  │
│  │    - View all out-of-stock items                            │  │
│  │  • Stock reservation logic:                                 │  │
│  │    - reserveStock(productId, variantSku, quantity)          │  │
│  │    - releaseStock(productId, variantSku, quantity)          │  │
│  │    - commitReservation(productId, variantSku, quantity)     │  │
│  │    - All using MongoDB atomic operators ($inc)              │  │
│  │  • Low-stock detection:                                     │  │
│  │    - On every stock change, check if quantity ≤ threshold   │  │
│  │    - Trigger notification to Seller if threshold crossed    │  │
│  │  • Stock availability check endpoint (public):              │  │
│  │    - GET /api/v1/products/{id}/availability                 │  │
│  │    - Returns stock status per variant (IN_STOCK,            │  │
│  │      LOW_STOCK, OUT_OF_STOCK) without exact numbers         │  │
│  │                                                              │  │
│  │  CATALOG MODULE ENHANCEMENTS:                                │  │
│  │  • Advanced filtering endpoint:                             │  │
│  │    - GET /api/v1/products?category=X&size=M,L               │  │
│  │      &color=red,blue&minPrice=50&maxPrice=500               │  │
│  │      &sort=price_asc&page=1&size=20                         │  │
│  │  • Filter aggregation endpoint:                             │  │
│  │    - GET /api/v1/products/filters?category=X                │  │
│  │    - Returns available sizes, colors, price range           │  │
│  │      for active products in that category                   │  │
│  │    - Dynamic: only shows sizes/colors that actually exist   │  │
│  │  • Product detail enrichment:                               │  │
│  │    - Include stock availability per variant                 │  │
│  │    - Include average rating + review count                  │  │
│  │    - Include related products (same category, limit 4)     │  │
│  │                                                              │  │
│  │  REDIS CACHING (CATALOG):                                   │  │
│  │  • Cache-aside implementation for:                          │  │
│  │    - Individual product detail: product:{id}                │  │
│  │    - Featured products list: products:featured              │  │
│  │    - Category tree: categories:tree                         │  │
│  │    - Stock status: stock:{productId}:{variant} (2min TTL)  │  │
│  │  • Cache invalidation hooks:                                │  │
│  │    - On product update → delete product:{id}               │  │
│  │    - On inventory change → delete stock:{productId}:*      │  │
│  │    - On category change → delete categories:tree            │  │
│  │  • Cache service abstraction:                               │  │
│  │    - CacheService with get/set/delete/deletePattern         │  │
│  │    - Used by all modules uniformly                          │  │
│  │                                                              │  │
│  │  SEARCH MODULE ENHANCEMENT:                                  │  │
│  │  • Autocomplete suggestions endpoint:                       │  │
│  │    - GET /api/v1/search/suggest?q=cla                       │  │
│  │    - Returns top 5 matching product names                   │  │
│  │    - Cached in Redis: search:suggest:{prefix}               │  │
│  │  • Full search with filters integration                     │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🎨 FRONTEND FOCUS:                                               │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  CUSTOMER — PRODUCT BROWSING (COMPLETE):                    │  │
│  │  • Product listing page:                                    │  │
│  │    - Grid layout (responsive: 4 cols → 2 cols → 1 col)     │  │
│  │    - Product card: image, name, price, rating, stock badge  │  │
│  │    - Pagination component (page numbers + prev/next)        │  │
│  │  • Filter sidebar/panel:                                    │  │
│  │    - Category filter (checkbox tree)                        │  │
│  │    - Size filter (chip/button group)                        │  │
│  │    - Color filter (color swatches)                          │  │
│  │    - Price range filter (range slider or min/max inputs)    │  │
│  │    - Active filter tags (removable)                         │  │
│  │    - "Clear all filters" action                             │  │
│  │    - Mobile: filter drawer/bottom sheet                     │  │
│  │  • Sort dropdown (Price ↑↓, Newest, Rating, Name)          │  │
│  │  • Product detail page:                                     │  │
│  │    - Image gallery (main image + thumbnails, click to zoom) │  │
│  │    - Size selector (buttons/chips, disabled if out of stock)│  │
│  │    - Color selector (swatches, disabled if out of stock)    │  │
│  │    - Quantity selector (number input with +/- buttons)      │  │
│  │    - Stock status badge (In Stock / Low Stock / Out)        │  │
│  │    - Price display (with compare-at-price strikethrough)    │  │
│  │    - Add to Cart button (disabled if out of stock)          │  │
│  │    - Add to Wishlist button (heart icon)                    │  │
│  │    - Product description (bilingual, tabbed or accordion)   │  │
│  │    - Related products carousel                              │  │
│  │  • Search bar component:                                    │  │
│  │    - Autocomplete dropdown                                  │  │
│  │    - Search results page                                    │  │
│  │    - Debounced input (300ms)                                │  │
│  │  • Home page completion:                                    │  │
│  │    - Hero banner section                                    │  │
│  │    - Featured products section                              │  │
│  │    - Categories showcase section                            │  │
│  │    - "New Arrivals" section                                 │  │
│  │                                                              │  │
│  │  SELLER — INVENTORY MANAGEMENT:                             │  │
│  │  • Inventory list page:                                     │  │
│  │    - Table: Product, Variant, Stock, Threshold, Status      │  │
│  │    - Color-coded status (green/yellow/red)                  │  │
│  │    - Inline edit for stock quantity                          │  │
│  │    - Bulk update capability                                 │  │
│  │    - Filter: All / Low Stock / Out of Stock                 │  │
│  │  • Inventory alerts panel (sidebar or top banner)           │  │
│  │    - Count of low-stock and out-of-stock items              │  │
│  │    - Quick links to affected products                       │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🔧 DEVOPS:                                                       │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  • MongoDB indexes for inventory collection created         │  │
│  │  • Redis connection verified for caching patterns           │  │
│  │  • Performance baseline: measure product listing query time │  │
│  │    with and without Redis cache                             │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  📦 DELIVERABLES:                                                  │
│  ✅ Seller can manage inventory (view, update, bulk edit)         │
│  ✅ Low-stock and out-of-stock tracking functional                │
│  ✅ Product listing with full filtering (size, color, price,      │
│     category, sort)                                               │
│  ✅ Product detail page with variant selection + stock status     │
│  ✅ Search with autocomplete working                              │
│  ✅ Home page complete (hero, featured, categories, new arrivals) │
│  ✅ Redis caching active for catalog + inventory reads            │
│  ✅ All pages responsive and RTL-compatible                       │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```
### 📅 Week 4 — Cart & Wishlist
```text
┌────────────────────────────────────────────────────────────────────┐
│  WEEK 4: CART & WISHLIST                                          │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  🎯 GOALS:                                                        │
│  • Build complete cart system (Redis-primary, MongoDB-backup)     │
│  • Implement guest cart + merge-on-login flow                     │
│  • Build wishlist module                                          │
│  • Cart ↔ Wishlist interactions (move items between them)         │
│                                                                    │
│  ⚙️ BACKEND FOCUS:                                                │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  CART MODULE (COMPLETE):                                     │  │
│  │  • Cart model + Redis storage structure                     │  │
│  │  • Cart operations:                                         │  │
│  │    - Add item: validate product, check stock, snapshot      │  │
│  │      price, store in Redis                                  │  │
│  │    - Update quantity: validate new quantity ≤ available      │  │
│  │      stock, update Redis                                    │  │
│  │    - Remove item: remove from Redis cart                    │  │
│  │    - Get cart: read from Redis, enrich with current         │  │
│  │      product data (images, current price for comparison)    │  │
│  │    - Clear cart: delete Redis key                           │  │
│  │  • Cart totals calculation:                                 │  │
│  │    - Subtotal (sum of item prices × quantities)             │  │
│  │    - Item count                                             │  │
│  │    - Calculated on every read (not stored separately)       │  │
│  │  • Guest cart support:                                      │  │
│  │    - Use session ID (generated client-side, UUID)           │  │
│  │    - Stored in Redis: cart:guest:{sessionId}                │  │
│  │    - TTL: 3 days (shorter than authenticated carts)         │  │
│  │  • Cart merge on login:                                     │  │
│  │    - POST /api/v1/cart/merge { guestSessionId }             │  │
│  │    - Merge strategy: guest items added to existing cart     │  │
│  │    - Conflict: if same product+variant exists, keep         │  │
│  │      higher quantity (up to stock limit)                    │  │
│  │    - Delete guest cart after merge                          │  │
│  │  • MongoDB async backup:                                    │  │
│  │    - On every cart modification, async write to MongoDB     │  │
│  │    - On Redis miss for authenticated user, restore from     │  │
│  │      MongoDB                                                │  │
│  │  • Price change detection:                                  │  │
│  │    - On cart retrieval, compare snapshot prices with        │  │
│  │      current prices                                         │  │
│  │    - Flag changed items in response:                        │  │
│  │      { priceChanged: true, oldPrice: 100, newPrice: 120 }  │  │
│  │  • Stale product handling:                                  │  │
│  │    - On cart retrieval, check if products still active      │  │
│  │    - Flag deactivated products: { unavailable: true }      │  │
│  │                                                              │  │
│  │  WISHLIST MODULE (COMPLETE):                                 │  │
│  │  • Wishlist model + wishlists collection                    │  │
│  │  • Wishlist operations:                                     │  │
│  │    - Add product to wishlist (idempotent)                   │  │
│  │    - Remove product from wishlist                           │  │
│  │    - Get wishlist (paginated, enriched with product data)   │  │
│  │    - Check if product is in wishlist:                       │  │
│  │      GET /api/v1/wishlist/check?productId=xxx               │  │
│  │      (Used by product detail page for heart icon state)     │  │
│  │    - Move to cart:                                          │  │
│  │      POST /api/v1/wishlist/items/{productId}/move-to-cart   │  │
│  │      { variantSku, quantity }                               │  │
│  │  • Wishlist count endpoint (for header badge)               │  │
│  │  • No duplicate products (enforced at DB level)             │  │
│  │                                                              │  │
│  │  AUTH MODULE ENHANCEMENT:                                    │  │
│  │  • Login response now includes cart item count              │  │
│  │  • Login flow triggers cart merge if guest session exists   │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🎨 FRONTEND FOCUS:                                               │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  CART UI:                                                   │  │
│  │  • Cart icon in header with badge (item count)              │  │
│  │  • Cart page (full page):                                   │  │
│  │    - Item list: image, name, variant (size/color),          │  │
│  │      quantity selector, unit price, line total, remove      │  │
│  │    - Price change warning banner per item                   │  │
│  │    - Unavailable product warning per item                   │  │
│  │    - Cart summary sidebar: subtotal, item count             │  │
│  │    - "Proceed to Checkout" button                           │  │
│  │    - "Continue Shopping" link                               │  │
│  │    - Empty cart state with CTA to browse                    │  │
│  │  • Mini-cart dropdown (optional, from header icon):         │  │
│  │    - Quick view of last 3 items                             │  │
│  │    - Subtotal                                               │  │
│  │    - "View Cart" and "Checkout" links                       │  │
│  │  • Add-to-cart confirmation:                                │  │
│  │    - Toast notification: "Added to cart"                    │  │
│  │    - Cart icon badge animates                               │  │
│  │  • Cart state service:                                      │  │
│  │    - Manages cart state across components                   │  │
│  │    - Exposes: items$, itemCount$, subtotal$                 │  │
│  │    - Guest session ID management                            │  │
│  │    - Merge trigger on login                                 │  │
│  │                                                              │  │
│  │  WISHLIST UI:                                               │  │
│  │  • Wishlist page:                                           │  │
│  │    - Product grid (similar to product listing)              │  │
│  │    - "Move to Cart" button per item (opens variant picker)  │  │
│  │    - "Remove" button per item                               │  │
│  │    - Empty wishlist state                                   │  │
│  │  • Heart icon on product cards and detail page:             │  │
│  │    - Filled = in wishlist, Outline = not in wishlist        │  │
│  │    - Click toggles wishlist state                           │  │
│  │    - Requires authentication (prompt login if guest)        │  │
│  │  • Wishlist badge in header (count)                         │  │
│  │                                                              │  │
│  │  PRODUCT DETAIL ENHANCEMENT:                                │  │
│  │  • "Add to Cart" now functional (with variant selection)    │  │
│  │  • Heart icon now functional (wishlist toggle)              │  │
│  │  • Quantity validation against stock                        │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🔧 DEVOPS:                                                       │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  • Redis memory usage monitoring for cart storage           │  │
│  │  • Cart TTL and expiration policies configured              │  │
│  │  • Load testing: simulate 100 concurrent cart operations    │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  📦 DELIVERABLES:                                                  │
│  ✅ Full cart flow: add → update quantity → remove → clear        │
│  ✅ Guest cart working with session ID                            │
│  ✅ Cart merge on login functional                                │
│  ✅ Price change + availability detection in cart                 │
│  ✅ Wishlist CRUD fully functional                                │
│  ✅ Heart icon toggle on product cards and detail page            │
│  ✅ Move from wishlist to cart working                            │
│  ✅ Cart and wishlist badges in header                            │
│  ✅ Redis as primary cart store verified                          │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```
### 📅 Week 5 — Order Placement & Payment
```text
┌────────────────────────────────────────────────────────────────────┐
│  WEEK 5: ORDER PLACEMENT & PAYMENT                                │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  🎯 GOALS:                                                        │
│  • Build the complete order module (creation, status, history)    │
│  • Integrate Stripe payment (PaymentIntent flow)                  │
│  • Implement checkout flow end-to-end                             │
│  • Handle payment success/failure webhooks                        │
│                                                                    │
│  ⚙️ BACKEND FOCUS:                                                │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  ORDER MODULE (COMPLETE):                                    │  │
│  │  • Order model + orders collection                          │  │
│  │  • Order number generator (STR-YYYYMMDD-NNNN):              │  │
│  │    - Atomic counter per day in MongoDB                      │  │
│  │    - Collision-free even under concurrent requests           │  │
│  │  • Order creation orchestration:                            │  │
│  │    - Validate cart not empty                                │  │
│  │    - Validate shipping address exists                       │  │
│  │    - Re-validate product availability + prices              │  │
│  │    - Reserve inventory (all-or-nothing)                     │  │
│  │    - Create order with PENDING status                       │  │
│  │    - Initiate payment intent                                │  │
│  │    - Return client secret                                   │  │
│  │  • Order status state machine:                              │  │
│  │    - Define valid transitions (see Flow 7 in Section 4)     │  │
│  │    - Reject invalid transitions with clear error messages   │  │
│  │    - Append to statusHistory on every transition            │  │
│  │  • Customer endpoints:                                      │  │
│  │    - POST /api/v1/orders (create)                           │  │
│  │    - GET /api/v1/orders (list own orders, paginated)        │  │
│  │    - GET /api/v1/orders/{id} (detail with full snapshots)   │  │
│  │    - POST /api/v1/orders/{id}/cancel (customer cancellation)│  │
│  │  • Seller endpoints:                                        │  │
│  │    - GET /api/v1/seller/orders (all orders, filterable)     │  │
│  │    - GET /api/v1/seller/orders/{id} (detail)                │  │
│  │    - PUT /api/v1/seller/orders/{id}/status (update status)  │  │
│  │  • Cancellation logic:                                      │  │
│  │    - Customer can cancel: only PENDING orders                │  │
│  │    - Seller can cancel: PENDING or CONFIRMED orders          │  │
│  │    - On cancel: release inventory + initiate refund          │  │
│  │  • Order expiry:                                            │  │
│  │    - PENDING orders not paid within 30 min → auto-cancel    │  │
│  │    - Scheduled task checks every 5 minutes                  │  │
│  │    - Releases inventory on expiry                           │  │
│  │                                                              │  │
│  │  PAYMENT MODULE (COMPLETE):                                  │  │
│  │  • Payment model + payments collection                      │  │
│  │  • Stripe integration:                                      │  │
│  │    - Create PaymentIntent with amount, currency, metadata   │  │
│  │    - Return clientSecret to frontend                        │  │
│  │  • Webhook handler:                                         │  │
│  │    - POST /api/v1/webhooks/stripe (public, signature-       │  │
│  │      verified)                                              │  │
│  │    - Handle events:                                         │  │
│  │      * payment_intent.succeeded → confirm order             │  │
│  │      * payment_intent.payment_failed → cancel order         │  │
│  │      * charge.refunded → update payment + order status      │  │
│  │    - Idempotent processing (check if event already handled) │  │
│  │    - Store raw webhook events for audit trail               │  │
│  │  • Refund initiation:                                       │  │
│  │    - Called by order module on cancellation                  │  │
│  │    - Creates Stripe refund                                  │  │
│  │    - Updates payment status                                 │  │
│  │  • Payment status query:                                    │  │
│  │    - GET /api/v1/orders/{id}/payment-status                 │  │
│  │    - Used by frontend to poll while waiting for webhook     │  │
│  │                                                              │  │
│  │  INVENTORY MODULE ENHANCEMENT:                               │  │
│  │  • Reservation timeout handling:                            │  │
│  │    - Scheduled task: release reservations for expired/       │  │
│  │      cancelled orders                                       │  │
│  │  • Stock commit on payment success:                         │  │
│  │    - Deduct reservedQuantity and quantity atomically        │  │
│  │                                                              │  │
│  │  CART MODULE ENHANCEMENT:                                    │  │
│  │  • Clear cart after successful order confirmation           │  │
│  │  • Clear from both Redis and MongoDB                        │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🎨 FRONTEND FOCUS:                                               │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  CHECKOUT FLOW:                                             │  │
│  │  • Checkout page (multi-step or single page):               │  │
│  │    - Step 1: Review cart items (read-only summary)          │  │
│  │    - Step 2: Select/add shipping address                    │  │
│  │      * List existing addresses (radio select)               │  │
│  │      * "Add new address" inline form                        │  │
│  │    - Step 3: Order summary                                  │  │
│  │      * Items with snapshots                                 │  │
│  │      * Subtotal, shipping, tax, total                       │  │
│  │    - Step 4: Payment                                        │  │
│  │      * Stripe Elements integration (card input)             │  │
│  │      * "Place Order" button                                 │  │
│  │  • Payment processing state:                                │  │
│  │    - Loading spinner during payment processing              │  │
│  │    - Stripe.confirmPayment() with clientSecret              │  │
│  │    - Handle success: redirect to order confirmation         │  │
│  │    - Handle failure: show error, allow retry                │  │
│  │  • Order confirmation page:                                 │  │
│  │    - Order number displayed prominently                     │  │
│  │    - "Thank you" message (bilingual)                        │  │
│  │    - Order summary                                          │  │
│  │    - "Continue Shopping" + "View Orders" buttons            │  │
│  │                                                              │  │
│  │  CUSTOMER ORDER PAGES:                                      │  │
│  │  • Order history page:                                      │  │
│  │    - List of orders (card layout)                           │  │
│  │    - Each card: order number, date, total, status badge,    │  │
│  │      item thumbnails                                        │  │
│  │    - Filter by status (tabs or dropdown)                    │  │
│  │    - Pagination                                             │  │
│  │  • Order detail page:                                       │  │
│  │    - Status timeline (visual progress bar)                  │  │
│  │    - Items list with snapshots                              │  │
│  │    - Shipping address                                       │  │
│  │    - Payment status                                         │  │
│  │    - "Cancel Order" button (if PENDING)                     │  │
│  │                                                              │  │
│  │  SELLER ORDER MANAGEMENT:                                   │  │
│  │  • Orders list page:                                        │  │
│  │    - Table layout with sortable columns                     │  │
│  │    - Filters: status, date range, search by order number    │  │
│  │    - Pagination                                             │  │
│  │  • Order detail page (Seller view):                         │  │
│  │    - All customer info + items + address                    │  │
│  │    - Status update dropdown (valid transitions only)        │  │
│  │    - Notes input for status updates                         │  │
│  │    - Status history timeline                                │  │
│  │    - "Cancel + Refund" action                               │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🔧 DEVOPS:                                                       │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  • Stripe account setup (test mode)                         │  │
│  │  • Stripe webhook endpoint registered (use Stripe CLI       │  │
│  │    for local testing or ngrok)                              │  │
│  │  • Stripe API keys configured in environment variables      │  │
│  │  • Webhook signing secret configured                        │  │
│  │  • Scheduled tasks configuration (order expiry check)       │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  📦 DELIVERABLES:                                                  │
│  ✅ Complete checkout flow: cart → address → payment → confirm    │
│  ✅ Stripe payment integration (test mode) working               │
│  ✅ Webhook handling for payment success/failure                  │
│  ✅ Order creation with inventory reservation                     │
│  ✅ Customer order history and detail pages                       │
│  ✅ Seller order management (view, update status)                 │
│  ✅ Cancellation + refund flow working                            │
│  ✅ Expired order auto-cancellation (scheduled task)              │
│  ✅ ⭐ MVP CORE IS NOW FUNCTIONAL (browse → cart → pay → order)  │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```
### 📅 Week 6 — Reviews & Notifications
```text
┌────────────────────────────────────────────────────────────────────┐
│  WEEK 6: REVIEWS & NOTIFICATIONS                                  │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  🎯 GOALS:                                                        │
│  • Build complete review system                                   │
│  • Build notification module (persistence + email)                │
│  • Implement WebSocket real-time notifications                    │
│  • Connect notification triggers across all modules               │
│                                                                    │
│  ⚙️ BACKEND FOCUS:                                                │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  REVIEW MODULE (COMPLETE):                                   │  │
│  │  • Review model + reviews collection                        │  │
│  │  • Submit review endpoint:                                  │  │
│  │    - POST /api/v1/products/{productId}/reviews              │  │
│  │    - Validate: one review per customer per product          │  │
│  │    - Check purchase history for "verified purchase" badge   │  │
│  │    - Rating: 1-5 integer                                    │  │
│  │    - Comment: text, max 1000 characters                     │  │
│  │  • List reviews for product:                                │  │
│  │    - GET /api/v1/products/{productId}/reviews               │  │
│  │    - Paginated, sorted by newest first                      │  │
│  │    - Includes reviewer display name + avatar                │  │
│  │  • Delete review:                                           │  │
│  │    - Customer can delete own review                         │  │
│  │    - Seller can delete any review (moderation)              │  │
│  │  • Rating aggregation:                                      │  │
│  │    - On review create/delete: recalculate averageRating     │  │
│  │      and reviewCount on the product document                │  │
│  │    - Uses MongoDB aggregation pipeline                      │  │
│  │    - Invalidate product cache in Redis                      │  │
│  │  • Rating distribution endpoint:                            │  │
│  │    - GET /api/v1/products/{productId}/reviews/distribution  │  │
│  │    - Returns: { 1: count, 2: count, 3: count, 4: count,    │  │
│  │      5: count }                                             │  │
│  │                                                              │  │
│  │  NOTIFICATION MODULE (COMPLETE):                             │  │
│  │  • Notification model + notifications collection            │  │
│  │  • Core notification service:                               │  │
│  │    - send(recipientId, type, titleI18n, messageI18n, data,  │  │
│  │      channel)                                               │  │
│  │    - Persists to MongoDB                                    │  │
│  │    - Dispatches to appropriate channel(s)                   │  │
│  │  • In-app notification endpoints:                           │  │
│  │    - GET /api/v1/notifications (paginated)                  │  │
│  │    - GET /api/v1/notifications/unread-count                 │  │
│  │    - PUT /api/v1/notifications/{id}/read                    │  │
│  │    - PUT /api/v1/notifications/read-all                     │  │
│  │    - DELETE /api/v1/notifications/{id}                      │  │
│  │  • Email notification service:                              │  │
│  │    - Integration with SendGrid or SMTP                      │  │
│  │    - Email templates (bilingual, HTML):                     │  │
│  │      * Welcome email                                        │  │
│  │      * Email verification                                   │  │
│  │      * Password reset                                       │  │
│  │      * Order confirmation                                   │  │
│  │      * Order shipped                                        │  │
│  │      * Order delivered                                      │  │
│  │      * Payment failed                                       │  │
│  │      * Refund processed                                     │  │
│  │    - Sent asynchronously (Spring @Async)                    │  │
│  │  • Notification trigger integration:                        │  │
│  │    - Wire up all events from notification catalog            │  │
│  │      (Section 7.2)                                          │  │
│  │    - Order status changes → customer notification           │  │
│  │    - New order → seller notification                        │  │
│  │    - Low stock → seller notification                        │  │
│  │    - New review → seller notification                       │  │
│  │    - Payment events → customer notification                 │  │
│  │                                                              │  │
│  │  WEBSOCKET IMPLEMENTATION:                                   │  │
│  │  • Spring WebSocket + STOMP + SockJS configuration          │  │
│  │  • JWT-based WebSocket authentication:                      │  │
│  │    - ChannelInterceptor validates token on CONNECT          │  │
│  │    - Maps authenticated user to WebSocket session           │  │
│  │  • User-specific message sending:                           │  │
│  │    - SimpMessagingTemplate.convertAndSendToUser(...)        │  │
│  │    - Sends to /user/queue/notifications                     │  │
│  │  • Notification service integration:                        │  │
│  │    - After persisting notification, push via WebSocket      │  │
│  │    - If user not connected, notification waits in DB        │  │
│  │    - Client fetches missed notifications on reconnect       │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🎨 FRONTEND FOCUS:                                               │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  REVIEWS UI:                                                │  │
│  │  • Review section on product detail page:                   │  │
│  │    - Average rating display (stars + number)                │  │
│  │    - Rating distribution bars (5-star breakdown)            │  │
│  │    - Review list (paginated)                                │  │
│  │    - Each review: stars, comment, author name, date,        │  │
│  │      "Verified Purchase" badge                              │  │
│  │  • Write review form:                                       │  │
│  │    - Star rating selector (interactive)                     │  │
│  │    - Comment textarea                                       │  │
│  │    - Submit button                                          │  │
│  │    - Shown only to authenticated customers                  │  │
│  │    - Hidden if already reviewed                             │  │
│  │  • Delete review button (own reviews or Seller moderation)  │  │
│  │                                                              │  │
│  │  NOTIFICATIONS UI:                                          │  │
│  │  • WebSocket service (Angular):                             │  │
│  │    - Connect on login with JWT                              │  │
│  │    - Subscribe to /user/queue/notifications                 │  │
│  │    - Reconnection with exponential backoff                  │  │
│  │    - Disconnect on logout                                   │  │
│  │  • Notification bell icon in header:                        │  │
│  │    - Unread count badge (red dot or number)                 │  │
│  │    - Click opens notification dropdown/panel                │  │
│  │    - List of recent notifications                           │  │
│  │    - Each notification: icon by type, title, time,          │  │
│  │      read/unread styling                                    │  │
│  │    - Click notification → navigate to relevant page         │  │
│  │    - "Mark all as read" action                              │  │
│  │    - "See all notifications" link → full page               │  │
│  │  • Notification full page:                                  │  │
│  │    - Paginated list of all notifications                    │  │
│  │    - Filter: All / Unread                                   │  │
│  │    - Delete individual notifications                        │  │
│  │  • Toast notifications:                                     │  │
│  │    - On receiving WebSocket message, show toast             │  │
│  │    - Auto-dismiss after 5 seconds                           │  │
│  │    - Click toast → navigate to relevant page                │  │
│  │    - Toast component: icon, title, message snippet          │  │
│  │                                                              │  │
│  │  EMAIL TEMPLATES (Design):                                  │  │
│  │  • Simple, clean email layout:                              │  │
│  │    - Sutra branding (logo, colors)                          │  │
│  │    - Bilingual content (detect user language preference)    │  │
│  │    - Responsive for email clients                           │  │
│  │    - CTA buttons linking to relevant pages                  │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🔧 DEVOPS:                                                       │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  • Email service account setup (SendGrid or SMTP)           │  │
│  │  • WebSocket testing (verify connections, message delivery)  │  │
│  │  • Notification delivery monitoring                         │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  📦 DELIVERABLES:                                                  │
│  ✅ Review submission, listing, deletion all working              │
│  ✅ Rating aggregation updates product display                    │
│  ✅ In-app notifications working via WebSocket                    │
│  ✅ Email notifications sending for key events                    │
│  ✅ Notification bell with unread count functional                │
│  ✅ Toast notifications on real-time events                       │
│  ✅ All notification triggers wired across modules                │
│  ✅ Seller receives alerts: new orders, low stock, new reviews    │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```
### 📅 Week 7 — Analytics Dashboard & Seller Tools
```text
┌────────────────────────────────────────────────────────────────────┐
│  WEEK 7: ANALYTICS DASHBOARD & SELLER TOOLS                      │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  🎯 GOALS:                                                        │
│  • Build the analytics module (aggregations + snapshots)          │
│  • Implement Seller dashboard with Chart.js visualizations        │
│  • Inventory health reporting                                     │
│  • Pre-computation and caching for dashboard performance          │
│                                                                    │
│  ⚙️ BACKEND FOCUS:                                                │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  ANALYTICS MODULE (COMPLETE):                                │  │
│  │  • Analytics snapshot model + analytics_snapshots collection │  │
│  │  • Dashboard summary endpoint:                              │  │
│  │    - GET /api/v1/seller/analytics/dashboard                 │  │
│  │      ?period=DAILY|WEEKLY|MONTHLY                           │  │
│  │      &from=YYYY-MM-DD&to=YYYY-MM-DD                        │  │
│  │    - Returns: summary cards + chart data + top products     │  │
│  │  • Revenue analytics:                                       │  │
│  │    - Revenue over time (data points for line chart)         │  │
│  │    - Revenue by category (data for bar chart)               │  │
│  │    - Period-over-period comparison (% change)               │  │
│  │  • Order analytics:                                         │  │
│  │    - Orders by status (doughnut chart)                      │  │
│  │    - Order volume over time (line chart)                    │  │
│  │    - Average order value trend                              │  │
│  │    - Completion rate, cancellation rate                     │  │
│  │  • Product analytics:                                       │  │
│  │    - Top selling products (by units and by revenue)         │  │
│  │    - Products with zero sales                               │  │
│  │    - Most wishlisted products                               │  │
│  │  • Inventory health endpoint:                               │  │
│  │    - GET /api/v1/seller/analytics/inventory-health          │  │
│  │    - Breakdown: healthy / low-stock / out-of-stock          │  │
│  │    - Estimated days-until-stockout per low-stock item       │  │
│  │    - Total inventory value                                  │  │
│  │  • Customer analytics (basic):                              │  │
│  │    - New registrations over time                            │  │
│  │    - Total customers                                        │  │
│  │    - Repeat vs one-time customers                           │  │
│  │  • MongoDB aggregation pipelines:                           │  │
│  │    - Revenue: orders $match + $group by date + $sort        │  │
│  │    - Top products: orders $unwind items + $group by product │  │
│  │    - Orders by status: orders $group by status              │  │
│  │  • Scheduled snapshot generation:                           │  │
│  │    - Daily at 02:00 AM: create DAILY snapshot               │  │
│  │    - Weekly (Monday 03:00 AM): create WEEKLY snapshot       │  │
│  │    - Monthly (1st, 04:00 AM): create MONTHLY snapshot      │  │
│  │    - Snapshots stored in analytics_snapshots collection     │  │
│  │  • Redis caching for dashboard:                             │  │
│  │    - Cache analytics responses with appropriate TTLs        │  │
│  │    - analytics:dashboard:{period} — TTL 30min-2hrs          │  │
│  │    - Invalidated by: snapshot generation or manual trigger  │  │
│  │                                                              │  │
│  │  SELLER EXPORT (BASIC):                                     │  │
│  │  • Export orders as CSV:                                    │  │
│  │    - GET /api/v1/seller/orders/export?format=csv            │  │
│  │      &status=DELIVERED&from=...&to=...                      │  │
│  │    - Streams CSV file as download                           │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🎨 FRONTEND FOCUS:                                               │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  SELLER DASHBOARD PAGE:                                     │  │
│  │  • KPI Summary cards (top row):                             │  │
│  │    - Total Revenue (with % change indicator)                │  │
│  │    - Orders Today                                           │  │
│  │    - Revenue This Month                                     │  │
│  │    - Average Order Value                                    │  │
│  │    - Each card: value, trend arrow (up/down), % change      │  │
│  │  • Period selector: Today / This Week / This Month / Custom │  │
│  │  • Revenue chart (Chart.js Line or Bar):                    │  │
│  │    - X-axis: time periods (days/weeks/months)               │  │
│  │    - Y-axis: revenue (SAR)                                  │  │
│  │    - Hover tooltips with exact values                       │  │
│  │    - Responsive sizing                                      │  │
│  │  • Orders by status chart (Chart.js Doughnut):              │  │
│  │    - Segments: Pending, Confirmed, Shipped, Delivered,      │  │
│  │      Cancelled                                              │  │
│  │    - Color coded                                            │  │
│  │    - Center: total order count                              │  │
│  │  • Top selling products table:                              │  │
│  │    - Rank, product name, units sold, revenue                │  │
│  │    - Click row → navigate to product                        │  │
│  │  • Inventory alerts panel:                                  │  │
│  │    - Warning icon + count of low-stock items                │  │
│  │    - Red icon + count of out-of-stock items                 │  │
│  │    - Expandable list of affected items                      │  │
│  │    - Quick action: "Update Stock" link per item             │  │
│  │  • Recent orders table:                                     │  │
│  │    - Last 5-10 orders                                       │  │
│  │    - Order number, customer, total, status, date            │  │
│  │    - Click row → navigate to order detail                   │  │
│  │  • Chart.js theming:                                        │  │
│  │    - Charts adapt to dark/light mode                        │  │
│  │    - Use CSS custom property values for chart colors        │  │
│  │    - Brand accent color for primary data series             │  │
│  │  • Chart RTL handling:                                      │  │
│  │    - Reverse data for RTL layouts where appropriate         │  │
│  │    - Legend positioning                                     │  │
│  │                                                              │  │
│  │  INVENTORY HEALTH PAGE:                                     │  │
│  │  • Visual breakdown: pie chart (healthy/low/out-of-stock)   │  │
│  │  • Detailed tables for low-stock and out-of-stock items     │  │
│  │  • Total inventory value display                            │  │
│  │                                                              │  │
│  │  ORDER EXPORT:                                              │  │
│  │  • "Export CSV" button on orders page                       │  │
│  │  • Date range selector for export                           │  │
│  │  • Status filter for export                                 │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🔧 DEVOPS:                                                       │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  • Scheduled tasks verified (snapshot generation)           │  │
│  │  • MongoDB aggregation pipeline performance profiled        │  │
│  │  • Analytics cache warming on application start             │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  📦 DELIVERABLES:                                                  │
│  ✅ Seller dashboard with all KPI cards and charts               │
│  ✅ Revenue, orders, product analytics all functional            │
│  ✅ Chart.js visualizations responsive + theme-aware             │
│  ✅ Inventory health reporting complete                          │
│  ✅ Analytics caching and pre-computation working                │
│  ✅ Scheduled snapshot generation verified                       │
│  ✅ CSV export for orders functional                             │
│  ✅ ⭐ FULL MVP COMPLETE — All features functional              │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```
### 📅 Week 8 — Testing, Error Handling & Edge Cases
```text
┌────────────────────────────────────────────────────────────────────┐
│  WEEK 8: TESTING, ERROR HANDLING & EDGE CASES                     │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  🎯 GOALS:                                                        │
│  • Harden all flows with proper error handling                    │
│  • Handle edge cases across all modules                           │
│  • Comprehensive API testing                                      │
│  • Frontend error states and loading states                       │
│                                                                    │
│  ⚙️ BACKEND FOCUS:                                                │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  ERROR HANDLING HARDENING:                                   │  │
│  │  • Global exception handler covers all cases:               │  │
│  │    - ValidationException → 400 with field-level errors      │  │
│  │    - ResourceNotFoundException → 404                        │  │
│  │    - UnauthorizedException → 401                            │  │
│  │    - ForbiddenException → 403                               │  │
│  │    - ConflictException → 409 (price changed, out of stock)  │  │
│  │    - RateLimitException → 429                               │  │
│  │    - StripeException → 502 (payment provider error)         │  │
│  │    - Generic Exception → 500 (with error ID for debugging)  │  │
│  │  • Consistent error response format:                        │  │
│  │    { status, error, message: {en, ar}, errorId, timestamp } │  │
│  │  • Request validation on ALL endpoints:                     │  │
│  │    - Input sanitization                                     │  │
│  │    - Bilingual validation messages                          │  │
│  │                                                              │  │
│  │  EDGE CASES HANDLED:                                        │  │
│  │  • Cart: Product deleted while in cart                      │  │
│  │  • Cart: Product deactivated while in cart                  │  │
│  │  • Cart: Price changed between add-to-cart and checkout     │  │
│  │  • Cart: Stock depleted between cart and order placement    │  │
│  │  • Order: Concurrent stock reservation (race condition)     │  │
│  │    - MongoDB atomic $inc prevents overselling               │  │
│  │  • Order: Payment webhook arrives before client redirect    │  │
│  │  • Order: Duplicate webhook events (idempotency)            │  │
│  │  • Review: Product deleted after review submitted           │  │
│  │  • Auth: Token refresh race condition (multiple tabs)       │  │
│  │  • Auth: Concurrent login from multiple devices             │  │
│  │  • File: Upload fails mid-way (cleanup orphan files)        │  │
│  │  • Redis: Connection failure (fallback to MongoDB)          │  │
│  │  • Wishlist: Adding a deactivated product                   │  │
│  │                                                              │  │
│  │  RATE LIMITING:                                              │  │
│  │  • Implement rate limiting on sensitive endpoints:           │  │
│  │    - POST /auth/login: 5 per minute per IP                  │  │
│  │    - POST /auth/register: 3 per hour per IP                 │  │
│  │    - POST /auth/forgot-password: 3 per hour per email       │  │
│  │    - POST /orders: 10 per minute per user                   │  │
│  │    - POST /reviews: 5 per hour per user                     │  │
│  │    - POST /files/upload: 20 per hour per user               │  │
│  │  • Redis-based sliding window counter                       │  │
│  │                                                              │  │
│  │  INPUT VALIDATION AUDIT:                                    │  │
│  │  • All DTOs have proper validation annotations              │  │
│  │  • XSS prevention on user-generated content (reviews,       │  │
│  │    product descriptions) — HTML sanitization                │  │
│  │  • SQL/NoSQL injection prevention (parameterized queries)   │  │
│  │  • File upload validation (magic bytes, not just extension) │  │
│  │                                                              │  │
│  │  LOGGING & MONITORING PREP:                                  │  │
│  │  • Structured logging (JSON format)                         │  │
│  │  • Request ID in every log line                             │  │
│  │  • Error ID in error responses (correlates to logs)         │  │
│  │  • Key metrics logged: response times, error rates          │  │
│  │  • Health check endpoint: GET /actuator/health              │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🎨 FRONTEND FOCUS:                                               │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  ERROR & LOADING STATES:                                    │  │
│  │  • Every page/component handles three states:               │  │
│  │    - Loading: skeleton screens or spinners                  │  │
│  │    - Error: user-friendly error message (bilingual)         │  │
│  │      with "Retry" action                                    │  │
│  │    - Empty: friendly empty state with relevant CTA          │  │
│  │  • Global error interceptor:                                │  │
│  │    - 500 errors → toast: "Something went wrong"             │  │
│  │    - 404 errors → "Not found" page                          │  │
│  │    - 409 errors → contextual message (price changed, etc.)  │  │
│  │    - Network errors → "Connection lost, retrying..."        │  │
│  │  • Form validation:                                         │  │
│  │    - Client-side validation matching backend rules          │  │
│  │    - Inline error messages (bilingual)                      │  │
│  │    - Server-side validation errors mapped to form fields    │  │
│  │                                                              │  │
│  │  SKELETON SCREENS:                                          │  │
│  │  • Product listing: card-shaped skeleton placeholders       │  │
│  │  • Product detail: image + text skeleton                    │  │
│  │  • Dashboard: chart area skeletons                          │  │
│  │  • Order list: row skeletons                                │  │
│  │                                                              │  │
│  │  EMPTY STATES (per page):                                   │  │
│  │  • Cart empty: illustration + "Start Shopping" button       │  │
│  │  • Wishlist empty: illustration + "Browse Products"         │  │
│  │  • No orders: illustration + "Place Your First Order"       │  │
│  │  • No reviews: "Be the first to review"                     │  │
│  │  • No search results: "Try different keywords"              │  │
│  │  • No notifications: "You're all caught up!"               │  │
│  │                                                              │  │
│  │  ACCESSIBILITY AUDIT (BASIC):                               │  │
│  │  • All images have alt text (bilingual)                     │  │
│  │  • Form labels associated with inputs                       │  │
│  │  • Focus management for modals and dropdowns                │  │
│  │  • Keyboard navigation for key flows                        │  │
│  │  • ARIA labels for icon-only buttons                        │  │
│  │  • Color contrast verification (both themes)                │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🔧 DEVOPS:                                                       │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  • API testing suite (Postman collection or equivalent)     │  │
│  │    - Happy path for every endpoint                          │  │
│  │    - Error cases for every endpoint                         │  │
│  │    - End-to-end flows (register → browse → cart → pay)      │  │
│  │  • Redis failover testing (disconnect Redis, verify         │  │
│  │    fallback behavior)                                       │  │
│  │  • Load testing: simulate 50 concurrent users browsing      │  │
│  │    and ordering                                             │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  📦 DELIVERABLES:                                                  │
│  ✅ All error scenarios handled gracefully (both ends)            │
│  ✅ Rate limiting active on sensitive endpoints                   │
│  ✅ Edge cases tested and resolved                                │
│  ✅ Loading/error/empty states on all pages                       │
│  ✅ Input validation comprehensive and bilingual                  │
│  ✅ API testing suite covering all endpoints                      │
│  ✅ Basic accessibility audit passed                              │
│  ✅ Structured logging in place                                   │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```
### 📅 Week 9 — UI/UX Polish & Responsive Design
```text
┌────────────────────────────────────────────────────────────────────┐
│  WEEK 9: UI/UX POLISH & RESPONSIVE DESIGN                        │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  🎯 GOALS:                                                        │
│  • Polish all UI components to production quality                 │
│  • Ensure full responsive design across all breakpoints           │
│  • Verify RTL + dark/light mode across all pages                  │
│  • Performance optimization (lazy loading, image optimization)    │
│                                                                    │
│  ⚙️ BACKEND FOCUS:                                                │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  PERFORMANCE OPTIMIZATION:                                   │  │
│  │  • API response time audit:                                 │  │
│  │    - Identify slow endpoints (> 500ms)                      │  │
│  │    - Add missing indexes                                    │  │
│  │    - Optimize MongoDB queries (projections, limits)         │  │
│  │  • Redis cache hit rate monitoring:                          │  │
│  │    - Verify caching is effective                            │  │
│  │    - Adjust TTLs based on observed patterns                 │  │
│  │  • Response compression (gzip) enabled                      │  │
│  │  • API pagination review:                                   │  │
│  │    - All list endpoints have sensible default page sizes    │  │
│  │    - Maximum page size enforced (prevent 10,000 item fetch) │  │
│  │  • Image URL optimization:                                  │  │
│  │    - Cloudinary transformation URLs generated per context   │  │
│  │    - Product list: thumbnail (300px)                        │  │
│  │    - Product detail: medium (800px)                         │  │
│  │    - Zoom: large (1200px)                                   │  │
│  │    - Format: auto (WebP where supported)                    │  │
│  │  • MongoDB connection pool tuning                           │  │
│  │  • Redis connection pool tuning                             │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🎨 FRONTEND FOCUS:                                               │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  RESPONSIVE DESIGN AUDIT:                                   │  │
│  │  • Test all pages at breakpoints:                           │  │
│  │    - Mobile: 320px, 375px, 414px                            │  │
│  │    - Tablet: 768px, 1024px                                  │  │
│  │    - Desktop: 1280px, 1440px, 1920px                        │  │
│  │  • Mobile-specific adjustments:                             │  │
│  │    - Hamburger menu navigation                              │  │
│  │    - Bottom navigation bar (optional)                       │  │
│  │    - Touch-friendly button sizes (min 44×44px)              │  │
│  │    - Swipe gestures for product image gallery               │  │
text
┌────────────────────────────────────────────────────────────────────┐
│  WEEK 9: UI/UX POLISH & RESPONSIVE DESIGN (Continued)            │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  🎨 FRONTEND FOCUS (Continued):                                   │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  RESPONSIVE DESIGN AUDIT (Continued):                       │  │
│  │  • Mobile-specific adjustments:                             │  │
│  │    - Hamburger menu navigation                              │  │
│  │    - Bottom navigation bar (optional)                       │  │
│  │    - Touch-friendly button sizes (min 44×44px)              │  │
│  │    - Swipe gestures for product image gallery               │  │
│  │    - Filter drawer (bottom sheet or side drawer)            │  │
│  │    - Sticky "Add to Cart" bar on product detail (mobile)    │  │
│  │    - Full-width cards on mobile                             │  │
│  │    - Collapsible sections on product detail                 │  │
│  │  • Tablet-specific adjustments:                             │  │
│  │    - 2-column product grid                                  │  │
│  │    - Side-by-side layout for checkout steps                 │  │
│  │    - Seller dashboard: charts stack vertically              │  │
│  │  • Desktop-specific refinements:                            │  │
│  │    - Hover effects on cards and buttons                     │  │
│  │    - Multi-column layouts where appropriate                 │  │
│  │    - Seller sidebar always visible                          │  │
│  │                                                              │  │
│  │  RTL COMPREHENSIVE AUDIT:                                   │  │
│  │  • Page-by-page RTL verification:                           │  │
│  │    - Navigation: links order, dropdown alignment            │  │
│  │    - Product grid: card order flips correctly               │  │
│  │    - Forms: labels, inputs, error messages aligned          │  │
│  │    - Tables: column order maintained or appropriately       │  │
│  │      mirrored                                               │  │
│  │    - Modals: close button position, content alignment       │  │
│  │    - Checkout flow: step indicator direction                │  │
│  │    - Charts: legend position, axis labels                   │  │
│  │    - Icons: directional icons (arrows, chevrons) flipped   │  │
│  │    - Pagination: page number order                          │  │
│  │    - Notification dropdown: alignment and text direction    │  │
│  │    - Breadcrumbs: separator direction                       │  │
│  │  • Fix any CSS logical property gaps:                       │  │
│  │    - Replace remaining margin-left/right with               │  │
│  │      margin-inline-start/end                                │  │
│  │    - Replace left/right positioning with                    │  │
│  │      inset-inline-start/end                                 │  │
│  │    - Verify border-radius on asymmetric shapes              │  │
│  │                                                              │  │
│  │  DARK/LIGHT MODE AUDIT:                                     │  │
│  │  • Page-by-page verification in both themes:                │  │
│  │    - All text readable (contrast ratio ≥ 4.5:1)            │  │
│  │    - Images with transparency look good on both backgrounds│  │
│  │    - Form inputs: border visibility, focus ring color       │  │
│  │    - Shadows: appropriate intensity per theme               │  │
│  │    - Status badges: colors distinguishable in both themes  │  │
│  │    - Charts: data series visible in both themes            │  │
│  │    - Scrollbars: styled for dark mode                       │  │
│  │    - Stripe Elements: themed to match current mode          │  │
│  │  • Edge cases:                                              │  │
│  │    - Theme switch mid-session (no flash/flicker)            │  │
│  │    - Theme persistence across page navigation               │  │
│  │    - Email templates: always light background (email        │  │
│  │      clients don't support dark mode reliably)              │  │
│  │                                                              │  │
│  │  PERFORMANCE OPTIMIZATION (Frontend):                       │  │
│  │  • Lazy loading:                                            │  │
│  │    - All feature modules lazy loaded (Angular router)       │  │
│  │    - Verify seller dashboard NOT loaded for customers       │  │
│  │    - Image lazy loading: loading="lazy" on off-screen images│  │
│  │    - Intersection Observer for infinite scroll (if used)    │  │
│  │  • Bundle optimization:                                     │  │
│  │    - Analyze bundle size (ng build --stats-json)            │  │
│  │    - Identify and tree-shake unused code                    │  │
│  │    - Chart.js: import only used chart types                 │  │
│  │    - Translation files: loaded on demand per language       │  │
│  │  • Image optimization:                                      │  │
│  │    - Use Cloudinary responsive transformations               │  │
│  │    - srcset for different screen densities                  │  │
│  │    - Placeholder blur-up while loading                      │  │
│  │    - Proper image dimensions (prevent layout shift)         │  │
│  │  • Caching strategy:                                        │  │
│  │    - Cache-Control headers on API responses                 │  │
│  │    - Service Worker for static assets (optional, PWA prep)  │  │
│  │    - Font files cached aggressively                         │  │
│  │                                                              │  │
│  │  ANIMATIONS & MICRO-INTERACTIONS:                           │  │
│  │  • Subtle transitions:                                      │  │
│  │    - Page transitions (fade in)                             │  │
│  │    - Card hover lift effect                                 │  │
│  │    - Button press feedback                                  │  │
│  │    - Cart badge bounce on item add                          │  │
│  │    - Notification toast slide-in                            │  │
│  │    - Skeleton shimmer effect                                │  │
│  │    - Filter panel slide (mobile)                            │  │
│  │    - Order status timeline animation                        │  │
│  │  • Keep animations subtle and performant:                   │  │
│  │    - Use transform and opacity only (GPU accelerated)       │  │
│  │    - Respect prefers-reduced-motion media query              │  │
│  │    - Duration: 200-300ms max for most transitions           │  │
│  │                                                              │  │
│  │  BRANDING CONSISTENCY:                                      │  │
│  │  • Sutra brand applied everywhere:                          │  │
│  │    - Logo in header, favicon, email templates               │  │
│  │    - Brand accent color (gold) used consistently            │  │
│  │    - Typography hierarchy consistent across pages           │  │
│  │    - Consistent spacing and padding system                  │  │
│  │    - Icon style consistent (outline vs filled)              │  │
│  │    - Button styles consistent (primary, secondary, ghost)   │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🔧 DEVOPS:                                                       │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  • Lighthouse audit on key pages:                           │  │
│  │    - Performance score target: > 85                         │  │
│  │    - Accessibility score target: > 90                       │  │
│  │    - Best Practices score target: > 90                      │  │
│  │  • Bundle size budget:                                      │  │
│  │    - Initial load: < 300KB (gzipped)                        │  │
│  │    - Lazy chunks: < 100KB each                              │  │
│  │  • Image CDN verified (Cloudinary serving via CDN)          │  │
│  │  • Gzip/Brotli compression enabled on server               │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  📦 DELIVERABLES:                                                  │
│  ✅ All pages fully responsive (mobile, tablet, desktop)          │
│  ✅ RTL mode pixel-perfect across all pages                       │
│  ✅ Dark/Light mode consistent across all pages                   │
│  ✅ Performance optimized (lazy loading, image optimization)      │
│  ✅ Animations and micro-interactions polished                    │
│  ✅ Lighthouse scores meet targets                                │
│  ✅ Brand consistency verified                                    │
│  ✅ Backend API response times optimized                          │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```
### 📅 Week 10 — Deployment, Security Hardening & Launch Prep
```text
┌────────────────────────────────────────────────────────────────────┐
│  WEEK 10: DEPLOYMENT, SECURITY HARDENING & LAUNCH PREP            │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  🎯 GOALS:                                                        │
│  • Deploy to production environment                               │
│  • Security hardening and penetration testing                     │
│  • Monitoring and alerting setup                                  │
│  • Final end-to-end testing in production environment             │
│  • Launch readiness checklist                                     │
│                                                                    │
│  ⚙️ BACKEND FOCUS:                                                │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  SECURITY HARDENING:                                         │  │
│  │  • HTTP Security Headers:                                   │  │
│  │    - Content-Security-Policy (strict)                       │  │
│  │    - X-Content-Type-Options: nosniff                        │  │
│  │    - X-Frame-Options: DENY                                  │  │
│  │    - Strict-Transport-Security (HSTS)                       │  │
│  │    - Referrer-Policy: strict-origin-when-cross-origin       │  │
│  │    - Permissions-Policy: camera=(), microphone=()           │  │
│  │  • CORS final configuration:                                │  │
│  │    - Production origin only (no wildcards)                  │  │
│  │    - Credentials: true                                      │  │
│  │    - Specific allowed methods and headers                   │  │
│  │  • API security review:                                     │  │
│  │    - All endpoints require appropriate authentication       │  │
│  │    - No sensitive data in logs (passwords, tokens, card     │  │
│  │      numbers)                                               │  │
│  │    - No sensitive data in error responses                   │  │
│  │    - Rate limiting verified on all critical endpoints       │  │
│  │    - Input validation on all endpoints verified             │  │
│  │  • Secrets management:                                      │  │
│  │    - All secrets in environment variables (not in code)     │  │
│  │    - JWT secret: strong random key (≥ 256 bits)             │  │
│  │    - Stripe keys: production keys configured                │  │
│  │    - MongoDB credentials: strong password                   │  │
│  │    - Redis: password protected                              │  │
│  │    - Cloudinary: API secret not exposed                     │  │
│  │  • Dependency security audit:                               │  │
│  │    - Check all Maven dependencies for known vulnerabilities │  │
│  │    - Update any flagged dependencies                        │  │
│  │  • MongoDB security:                                        │  │
│  │    - Authentication enabled                                 │  │
│  │    - Network access restricted (IP whitelist)               │  │
│  │    - TLS enabled for connections                            │  │
│  │    - Backup scheduled (Atlas automated backups)             │  │
│  │  • Redis security:                                          │  │
│  │    - Password authentication required                       │  │
│  │    - No public network exposure                             │  │
│  │    - TLS for connections (if remote Redis)                  │  │
│  │  • Stripe production setup:                                 │  │
│  │    - Switch from test to production API keys                │  │
│  │    - Production webhook endpoint registered                 │  │
│  │    - Webhook signing secret updated                         │  │
│  │    - Test a real payment end-to-end                         │  │
│  │                                                              │  │
│  │  PRODUCTION CONFIGURATION:                                   │  │
│  │  • Spring profiles: application-prod.yml                    │  │
│  │    - Production MongoDB URI (Atlas)                         │  │
│  │    - Production Redis connection                            │  │
│  │    - Production Stripe keys                                 │  │
│  │    - Production Cloudinary credentials                      │  │
│  │    - Production email service credentials                   │  │
│  │    - Logging: INFO level (not DEBUG)                        │  │
│  │    - CORS: production frontend URL only                     │  │
│  │  • JVM tuning:                                              │  │
│  │    - Heap size: -Xms512m -Xmx1024m (adjust per server)     │  │
│  │    - GC tuning: G1GC (default, usually fine)                │  │
│  │  • Health and readiness endpoints:                          │  │
│  │    - /actuator/health (includes MongoDB, Redis checks)      │  │
│  │    - /actuator/info (app version, build time)               │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🎨 FRONTEND FOCUS:                                               │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  PRODUCTION BUILD:                                          │  │
│  │  • Angular production build:                                │  │
│  │    - AOT compilation                                        │  │
│  │    - Tree shaking                                           │  │
│  │    - Minification and uglification                          │  │
│  │    - Source maps generated (but NOT deployed publicly)       │  │
│  │    - Environment file: production API URL                   │  │
│  │  • Verify all environment-specific configs:                 │  │
│  │    - API base URL (production backend)                      │  │
│  │    - Stripe publishable key (production)                    │  │
│  │    - WebSocket URL (production)                             │  │
│  │    - Cloudinary cloud name                                  │  │
│  │                                                              │  │
│  │  SEO & META (Basic):                                        │  │
│  │  • Page titles (bilingual) for all routes:                  │  │
│  │    - Home: "Sutra | سُترة — Clothing Store"                 │  │
│  │    - Products: "Products | المنتجات — Sutra"                │  │
│  │    - Product detail: dynamic title with product name        │  │
│  │  • Meta description tags (bilingual)                        │  │
│  │  • Open Graph tags for social sharing (product pages):      │  │
│  │    - og:title, og:description, og:image                     │  │
│  │  • Favicon and web app manifest:                            │  │
│  │    - Favicon at multiple sizes                              │  │
│  │    - Apple touch icon                                       │  │
│  │    - manifest.json with Sutra branding                      │  │
│  │  • Sitemap.xml generation (basic, static routes)            │  │
│  │  • robots.txt                                               │  │
│  │                                                              │  │
│  │  404 PAGE:                                                  │  │
│  │  • Custom 404 page with:                                    │  │
│  │    - Sutra branding                                         │  │
│  │    - Bilingual message                                      │  │
│  │    - Navigation back to home/products                       │  │
│  │    - Search bar                                             │  │
│  │                                                              │  │
│  │  FINAL CROSS-BROWSER TESTING:                               │  │
│  │  • Chrome (latest)                                          │  │
│  │  • Firefox (latest)                                         │  │
│  │  • Safari (latest — critical for iOS)                       │  │
│  │  • Edge (latest)                                            │  │
│  │  • Mobile Safari (iOS)                                      │  │
│  │  • Chrome Mobile (Android)                                  │  │
│  │  • Samsung Internet (popular in target market)              │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  🔧 DEVOPS (CRITICAL WEEK):                                       │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  INFRASTRUCTURE SETUP:                                      │  │
│  │  • Production server provisioning:                          │  │
│  │    Option A: VPS (DigitalOcean/Hetzner):                    │  │
│  │      - 4GB RAM, 2 vCPU minimum                              │  │
│  │      - Ubuntu 22.04 LTS                                     │  │
│  │      - Docker + Docker Compose installed                    │  │
│  │    Option B: Cloud Platform (AWS/GCP/Azure):                │  │
│  │      - App Service / Cloud Run / Elastic Beanstalk          │  │
│  │      - Managed services for MongoDB (Atlas) and Redis       │  │
│  │                                                              │  │
│  │  DEPLOYMENT ARCHITECTURE:                                   │  │
│  │  ┌─────────────────────────────────────────────────────┐    │  │
│  │  │                    PRODUCTION                       │    │  │
│  │  │                                                     │    │  │
│  │  │  ┌──────────────────────────────────────────────┐   │    │  │
│  │  │  │           NGINX (Reverse Proxy)              │   │    │  │
│  │  │  │  - SSL termination (Let's Encrypt)           │   │    │  │
│  │  │  │  - Serve Angular static files                │   │    │  │
│  │  │  │  - Proxy /api/** to Spring Boot              │   │    │  │
│  │  │  │  - Proxy /ws/** to Spring Boot (WebSocket)   │   │    │  │
│  │  │  │  - Gzip compression                          │   │    │  │
│  │  │  │  - Rate limiting (additional layer)          │   │    │  │
│  │  │  │  - Security headers                          │   │    │  │
│  │  │  │  - Static file caching (1 year, hashed)      │   │    │  │
│  │  │  └──────────────────────────────────────────────┘   │    │  │
│  │  │             │                    │                   │    │  │
│  │  │     /api/** │           static/* │                   │    │  │
│  │  │             ▼                    ▼                   │    │  │
│  │  │  ┌────────────────┐   ┌──────────────────┐          │    │  │
│  │  │  │  Spring Boot   │   │  Angular Static  │          │    │  │
│  │  │  │  (Docker)      │   │  Files (NGINX)   │          │    │  │
│  │  │  │  Port 8080     │   │                  │          │    │  │
│  │  │  └───────┬────────┘   └──────────────────┘          │    │  │
│  │  │          │                                           │    │  │
│  │  │    ┌─────┼──────────────┐                            │    │  │
│  │  │    │     │              │                            │    │  │
│  │  │    ▼     ▼              ▼                            │    │  │
│  │  │  ┌────┐ ┌─────┐  ┌───────────┐                     │    │  │
│  │  │  │Redis│ │Mongo│  │Cloudinary │                     │    │  │
│  │  │  │Local│ │Atlas│  │  (Cloud)  │                     │    │  │
│  │  │  └────┘ └─────┘  └───────────┘                     │    │  │
│  │  │                                                     │    │  │
│  │  └─────────────────────────────────────────────────────┘    │  │
│  │                                                              │  │
│  │  DOCKER COMPOSE (Production):                                │  │
│  │  • Services:                                                │  │
│  │    - nginx (reverse proxy + static files)                   │  │
│  │    - spring-boot-app (backend)                              │  │
│  │    - redis (if self-hosted, otherwise managed)              │  │
│  │  • Volumes:                                                 │  │
│  │    - Redis data persistence                                 │  │
│  │    - NGINX config                                           │  │
│  │    - SSL certificates                                       │  │
│  │    - Application logs                                       │  │
│  │  • Restart policies: always                                 │  │
│  │  • Resource limits defined                                  │  │
│  │                                                              │  │
│  │  SSL/TLS:                                                   │  │
│  │  • Domain: sutra.store (or chosen domain)                   │  │
│  │  • Let's Encrypt certificate (auto-renewal via certbot)     │  │
│  │  • Force HTTPS redirect                                     │  │
│  │  • HSTS enabled                                             │  │
│  │                                                              │  │
│  │  CI/CD PIPELINE (Basic):                                    │  │
│  │  • GitHub Actions or GitLab CI:                             │  │
│  │    - On push to main:                                       │  │
│  │      1. Run backend tests (if any)                          │  │
│  │      2. Build Spring Boot JAR                               │  │
│  │      3. Build Angular production bundle                     │  │
│  │      4. Build Docker image                                  │  │
│  │      5. Push to container registry                          │  │
│  │      6. SSH to server and pull new image                    │  │
│  │      7. Docker Compose restart with new image               │  │
│  │      8. Health check verification                           │  │
│  │    - Rollback: previous image tag always available          │  │
│  │                                                              │  │
│  │  MONITORING & ALERTING:                                     │  │
│  │  • Uptime monitoring:                                       │  │
│  │    - UptimeRobot or BetterUptime (free tier)                │  │
│  │    - Monitor: homepage, API health endpoint, WebSocket      │  │
│  │    - Alert via email + SMS on downtime                      │  │
│  │  • Application monitoring:                                  │  │
│  │    - Spring Boot Actuator endpoints:                        │  │
│  │      /health, /metrics, /info                               │  │
│  │    - Log aggregation: structured JSON logs to file          │  │
│  │    - Optional: Grafana + Prometheus for metrics dashboard   │  │
│  │      (can be added post-launch)                             │  │
│  │  • Error tracking:                                          │  │
│  │    - Sentry (free tier) for both frontend and backend       │  │
│  │    - Captures unhandled exceptions with context             │  │
│  │    - Source map upload for Angular error deobfuscation       │  │
│  │  • MongoDB Atlas monitoring:                                │  │
│  │    - Built-in performance advisor                           │  │
│  │    - Alert on slow queries (> 1000ms)                       │  │
│  │    - Alert on connection count spikes                       │  │
│  │                                                              │  │
│  │  BACKUP STRATEGY:                                           │  │
│  │  • MongoDB Atlas: automated daily backups (included)        │  │
│  │  • Redis: persistence enabled (RDB snapshots)               │  │
│  │  • Cloudinary: images are durable by default                │  │
│  │  • Application config: stored in Git (secrets excluded)     │  │
│  │                                                              │  │
│  │  DNS CONFIGURATION:                                         │  │
│  │  • A record → server IP                                     │  │
│  │  • CNAME: www → root domain                                 │  │
│  │  • TTL: 300s initially (fast failover during launch)        │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  📋 END-TO-END TESTING IN PRODUCTION:                              │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  CRITICAL PATH TESTING:                                     │  │
│  │  ☐ Customer registers → receives verification email         │  │
│  │  ☐ Customer logs in → sees personalized content             │  │
│  │  ☐ Browse products → filters work → search works            │  │
│  │  ☐ Product detail → variant selection → stock shows         │  │
│  │  ☐ Add to cart → cart updates → badge updates               │  │
│  │  ☐ Add to wishlist → heart icon toggles                     │  │
│  │  ☐ Checkout flow → address selection → payment              │  │
│  │  ☐ Stripe payment (real test charge) → webhook received     │  │
│  │  ☐ Order confirmed → customer notified (WebSocket + email)  │  │
│  │  ☐ Seller notified of new order (WebSocket + email)         │  │
│  │  ☐ Seller updates order status → customer notified          │  │
│  │  ☐ Customer writes review → appears on product page         │  │
│  │  ☐ Seller views dashboard → charts render with data         │  │
│  │  ☐ Language switch (EN ↔ AR) works on all pages             │  │
│  │  ☐ Theme switch (Light ↔ Dark) works on all pages           │  │
│  │  ☐ Mobile responsiveness verified on real devices            │  │
│  │  ☐ Guest cart → login → cart merge works                    │  │
│  │  ☐ Order cancellation → refund initiated                    │  │
│  │  ☐ Low stock alert → seller notified                        │  │
│  │  ☐ Forgot password → reset email → new password works       │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  📦 DELIVERABLES:                                                  │
│  ✅ Production environment fully operational                      │
│  ✅ SSL/HTTPS configured and forced                               │
│  ✅ CI/CD pipeline deploying on push to main                      │
│  ✅ Security headers and CORS production-configured               │
│  ✅ Monitoring and alerting active                                │
│  ✅ Stripe production mode verified                               │
│  ✅ All critical paths tested in production                       │
│  ✅ SEO basics in place (meta, OG, sitemap, robots)              │
│  ✅ Backup strategy verified                                      │
│  ✅ Cross-browser testing passed                                  │
│  ✅ 🚀 APPLICATION IS LAUNCH-READY                               │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```
## 📊 Complete Timeline Summary
```text
┌──────────────────────────────────────────────────────────────────────────┐
│                        10-WEEK EXECUTION SUMMARY                         │
├──────┬───────────────────────────────┬───────────────────────────────────┤
│ Week │ Theme                         │ Key Milestone                     │
├──────┼───────────────────────────────┼───────────────────────────────────┤
│  1   │ Foundation & Auth             │ ✅ Auth working, shells ready     │
├──────┼───────────────────────────────┼───────────────────────────────────┤
│  2   │ Profile & Catalog             │ ✅ Products browsable, Seller     │
│      │                               │    can create products            │
├──────┼───────────────────────────────┼───────────────────────────────────┤
│  3   │ Inventory & Advanced Browsing │ ✅ Full browsing experience,      │
│      │                               │    stock tracking, Redis caching  │
├──────┼───────────────────────────────┼───────────────────────────────────┤
│  4   │ Cart & Wishlist               │ ✅ Shopping cart + wishlist        │
│      │                               │    fully functional               │
├──────┼───────────────────────────────┼───────────────────────────────────┤
│  5   │ Orders & Payment              │ ⭐ MVP CORE COMPLETE             │
│      │                               │    Browse → Cart → Pay → Order    │
├──────┼───────────────────────────────┼───────────────────────────────────┤
│  6   │ Reviews & Notifications       │ ✅ Social proof + real-time       │
│      │                               │    communication layer            │
├──────┼───────────────────────────────┼───────────────────────────────────┤
│  7   │ Analytics & Seller Dashboard  │ ⭐ FULL FEATURE COMPLETE         │
│      │                               │    Seller has complete toolset    │
├──────┼───────────────────────────────┼───────────────────────────────────┤
│  8   │ Testing & Error Handling      │ ✅ Production-grade error         │
│      │                               │    handling, edge cases resolved  │
├──────┼───────────────────────────────┼───────────────────────────────────┤
│  9   │ UI/UX Polish & Performance    │ ✅ Pixel-perfect, responsive,     │
│      │                               │    RTL, themes, optimized         │
├──────┼───────────────────────────────┼───────────────────────────────────┤
│  10  │ Deployment & Launch           │ 🚀 PRODUCTION LAUNCH             │
│      │                               │    Deployed, secured, monitored   │
├──────┴───────────────────────────────┴───────────────────────────────────┤
│                                                                          │
│  MODULE COMPLETION TIMELINE:                                             │
│                                                                          │
│  auth-module        ████░░░░░░░░░░░░░░░░  Week 1                       │
│  profile-module     ░░██░░░░░░░░░░░░░░░░  Week 2                       │
│  file-module        ░░██░░░░░░░░░░░░░░░░  Week 2                       │
│  catalog-module     ░░████░░░░░░░░░░░░░░  Weeks 2-3                    │
│  search-module      ░░░░██░░░░░░░░░░░░░░  Week 3                       │
│  inventory-module   ░░░░██░░░░░░░░░░░░░░  Week 3                       │
│  cart-module        ░░░░░░██░░░░░░░░░░░░  Week 4                       │
│  wishlist-module    ░░░░░░██░░░░░░░░░░░░  Week 4                       │
│  order-module       ░░░░░░░░██░░░░░░░░░░  Week 5                       │
│  payment-module     ░░░░░░░░██░░░░░░░░░░  Week 5                       │
│  review-module      ░░░░░░░░░░██░░░░░░░░  Week 6                       │
│  notification-module░░░░░░░░░░██░░░░░░░░  Week 6                       │
│  analytics-module   ░░░░░░░░░░░░██░░░░░░  Week 7                       │
│                                                                          │
│  CROSS-CUTTING WORK:                                                     │
│  Redis caching      ░░░░██░░░░░░░░░░░░░░  Week 3 (then ongoing)       │
│  WebSocket          ░░░░░░░░░░██░░░░░░░░  Week 6                       │
│  i18n/RTL           ████████████████████  Continuous (Weeks 1-10)      │
│  Dark/Light theme   ████████████████████  Continuous (Weeks 1-10)      │
│  Error handling     ░░░░░░░░░░░░░░██░░░░  Week 8 (hardening)          │
│  Security           ██░░░░░░░░░░░░░░░░██  Weeks 1 + 10               │
│  DevOps/Deploy      ██░░░░░░░░░░░░░░░░██  Weeks 1 + 10               │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```
## 🏁 Post-Launch Roadmap (Future Enhancements)
```text
┌────────────────────────────────────────────────────────────────────┐
│               POST-LAUNCH ENHANCEMENT BACKLOG                      │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  PRIORITY 1 (Weeks 11-12 if timeline extends):                    │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  • Coupon/Discount module:                                  │  │
│  │    - Coupon codes (percentage, fixed amount)                │  │
│  │    - Auto-apply discount rules                              │  │
│  │    - Usage limits per coupon and per customer               │  │
│  │  • Order tracking (basic):                                  │  │
│  │    - Tracking number field on order                         │  │
│  │    - Customer can see tracking info                         │  │
│  │  • PWA support:                                             │  │
│  │    - Service worker for offline browsing                    │  │
│  │    - Push notifications (web push)                          │  │
│  │    - Install prompt                                         │  │
│  │  • Product sorting enhancements:                            │  │
│  │    - "Bestselling" sort (based on analytics)                │  │
│  │    - "Trending" products                                    │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  PRIORITY 2 (Month 4+):                                           │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  • Shipping provider integration (Aramex/SMSA):             │  │
│  │    - Auto shipping label generation                         │  │
│  │    - Real-time tracking updates                             │  │
│  │    - Shipping cost calculation                              │  │
│  │  • Customer accounts enhancement:                           │  │
│  │    - Social login (Google, Apple)                           │  │
│  │    - Order re-order (one-click reorder)                     │  │
│  │  • Email marketing integration:                             │  │
│  │    - Abandoned cart recovery emails                         │  │
│  │    - Product back-in-stock notifications                    │  │
│  │  • Advanced analytics:                                      │  │
│  │    - Customer lifetime value                                │  │
│  │    - Conversion funnel analysis                             │  │
│  │    - Return/refund analytics                                │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  PRIORITY 3 (Month 6+):                                           │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  • Multi-currency support                                   │  │
│  │  • Size guide / fit recommender                             │  │
│  │  • Product collections / lookbooks                          │  │
│  │  • Customer segmentation                                    │  │
│  │  • A/B testing framework                                    │  │
│  │  • Elasticsearch for advanced search (if catalog grows)     │  │
│  │  • Microservice extraction (if team/traffic grows)          │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```
## ✅ Final Architecture Validation Checklist
```text
┌────────────────────────────────────────────────────────────────────┐
│              ARCHITECTURE VALIDATION CHECKLIST                      │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  FUNCTIONAL COMPLETENESS:                                          │
│  ☑ Customer can browse, search, filter products                   │
│  ☑ Customer can manage cart (add, update, remove)                 │
│  ☑ Customer can manage wishlist                                   │
│  ☑ Customer can checkout and pay (Stripe)                         │
│  ☑ Customer can track order status                                │
│  ☑ Customer can write and read reviews                            │
│  ☑ Customer receives real-time notifications                      │
│  ☑ Seller can manage products (CRUD + images + variants)          │
│  ☑ Seller can manage inventory (stock + thresholds)               │
│  ☑ Seller can manage orders (view + update status)                │
│  ☑ Seller can view analytics dashboard with charts                │
│  ☑ Seller receives alerts (new orders, low stock, reviews)        │
│                                                                    │
│  NON-FUNCTIONAL REQUIREMENTS:                                      │
│  ☑ Bilingual (Arabic + English) with RTL support                  │
│  ☑ Dark/Light mode with persistence                               │
│  ☑ Responsive design (mobile, tablet, desktop)                    │
│  ☑ JWT authentication with refresh token rotation                 │
│  ☑ Redis caching for performance                                  │
│  ☑ Real-time WebSocket notifications                              │
│  ☑ Secure file uploads via Cloudinary                             │
│  ☑ Stripe payment with webhook handling                           │
│  ☑ Rate limiting on sensitive endpoints                           │
│  ☑ Structured logging and error tracking                          │
│  ☑ CI/CD pipeline for automated deployment                       │
│  ☑ Monitoring and alerting                                        │
│  ☑ Backup strategy                                                │
│                                                                    │
│  DESIGN PRINCIPLES HONORED:                                        │
│  ☑ Single-brand (no marketplace complexity)                       │
│  ☑ Seller = Admin (no separate admin role)                        │
│  ☑ Monolith with clean module boundaries                          │
│  ☑ Service-oriented internal architecture (future-proof)          │
│  ☑ Data ownership clear per module                                │
│  ☑ No tight coupling between modules                              │
│  ☑ Cache-friendly architecture                                    │
│  ☑ Snapshot-based order data (immutable after creation)           │
│  ☑ Atomic inventory operations (no overselling)                   │
│  ☑ Idempotent webhook handling                                    │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```
This concludes the complete Sutra (سُترة) system architecture blueprint and execution roadmap. The design provides a production-ready foundation that is practical for a small team to build in 10 weeks, while maintaining clean module boundaries that allow future scaling — whether that means extracting microservices, adding new features, or growing the team.