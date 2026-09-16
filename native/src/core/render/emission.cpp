#include "core/render/emission.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

namespace {

uint64_t buildCellStableKey(uint64_t tileKey, uint32_t localCellIndex) {
    uint64_t seed = tileKey;
    seed ^= static_cast<uint64_t>(localCellIndex) + 0x9e3779b97f4a7c15ull + (seed << 6) + (seed >> 2);
    return seed;
}

} // namespace

void EmissionCellRTree::clear() {
    root_.reset();
}

EmissionCellRTree::Rect EmissionCellRTree::combineRect(const Rect &a, const Rect &b) {
    return Rect{
        .minX = std::min(a.minX, b.minX),
        .minY = std::min(a.minY, b.minY),
        .maxX = std::max(a.maxX, b.maxX),
        .maxY = std::max(a.maxY, b.maxY),
    };
}

bool EmissionCellRTree::intersects(const Rect &a, const Rect &b) {
    return !(a.maxX < b.minX || a.minX > b.maxX || a.maxY < b.minY || a.minY > b.maxY);
}

float EmissionCellRTree::area(const Rect &rect) {
    return std::max(rect.maxX - rect.minX, 0.0f) * std::max(rect.maxY - rect.minY, 0.0f);
}

EmissionCellRTree::Rect EmissionCellRTree::computeNodeRect(const Node &node) {
    Rect rect = node.entries.front().rect;
    for (size_t i = 1; i < node.entries.size(); i++) {
        rect = combineRect(rect, node.entries[i].rect);
    }
    return rect;
}

EmissionCellRTree::Rect EmissionCellRTree::computeEntriesRect(const EntryList &entries) {
    Rect rect = entries.front().rect;
    for (size_t i = 1; i < entries.size(); i++) {
        rect = combineRect(rect, entries[i].rect);
    }
    return rect;
}

float EmissionCellRTree::enlargement(const Rect &original, const Rect &extra) {
    return area(combineRect(original, extra)) - area(original);
}

EmissionCellRTree::Entry EmissionCellRTree::makeLeafEntry(const Rect &rect, const Value &value) {
    Entry entry;
    entry.rect = rect;
    entry.value = value;
    return entry;
}

EmissionCellRTree::Entry EmissionCellRTree::makeChildEntry(std::unique_ptr<Node> child) {
    Entry entry;
    entry.rect = computeNodeRect(*child);
    entry.child = std::move(child);
    return entry;
}

void EmissionCellRTree::collectLeafEntries(Node &node, EntryList &out) {
    if (node.leaf) {
        for (auto &entry : node.entries) {
            out.push_back(std::move(entry));
        }
        node.entries.clear();
        return;
    }

    for (auto &entry : node.entries) {
        collectLeafEntries(*entry.child, out);
    }
    node.entries.clear();
}

std::unique_ptr<EmissionCellRTree::Node> EmissionCellRTree::splitNode(Node &node) {
    EntryList entries = std::move(node.entries);
    node.entries.clear();
    node.entries.reserve(kMaxEntries);

    auto sibling = std::make_unique<Node>();
    sibling->leaf = node.leaf;
    sibling->entries.reserve(kMaxEntries);

    std::vector<bool> assigned(entries.size(), false);

    size_t seedA = 0;
    size_t seedB = 1;
    float maxWaste = std::numeric_limits<float>::lowest();
    for (size_t i = 0; i < entries.size(); i++) {
        for (size_t j = i + 1; j < entries.size(); j++) {
            float waste = area(combineRect(entries[i].rect, entries[j].rect)) - area(entries[i].rect) -
                          area(entries[j].rect);
            if (waste > maxWaste) {
                maxWaste = waste;
                seedA = i;
                seedB = j;
            }
        }
    }

    auto assignEntry = [&](Node &target, size_t index) {
        assigned[index] = true;
        target.entries.push_back(std::move(entries[index]));
    };

    assignEntry(node, seedA);
    assignEntry(*sibling, seedB);

    size_t remaining = entries.size() - 2;
    while (remaining > 0) {
        if (node.entries.size() + remaining == kMinEntries) {
            for (size_t i = 0; i < entries.size(); i++) {
                if (!assigned[i]) {
                    assignEntry(node, i);
                    remaining--;
                }
            }
            break;
        }

        if (sibling->entries.size() + remaining == kMinEntries) {
            for (size_t i = 0; i < entries.size(); i++) {
                if (!assigned[i]) {
                    assignEntry(*sibling, i);
                    remaining--;
                }
            }
            break;
        }

        const Rect rectA = computeEntriesRect(node.entries);
        const Rect rectB = computeEntriesRect(sibling->entries);

        size_t chosenIndex = 0;
        float bestDiff = std::numeric_limits<float>::lowest();
        for (size_t i = 0; i < entries.size(); i++) {
            if (assigned[i]) {
                continue;
            }

            float enlargeA = enlargement(rectA, entries[i].rect);
            float enlargeB = enlargement(rectB, entries[i].rect);
            float diff = std::abs(enlargeA - enlargeB);
            if (diff > bestDiff) {
                bestDiff = diff;
                chosenIndex = i;
            }
        }

        float enlargeA = enlargement(rectA, entries[chosenIndex].rect);
        float enlargeB = enlargement(rectB, entries[chosenIndex].rect);
        if (enlargeA < enlargeB) {
            assignEntry(node, chosenIndex);
        } else if (enlargeB < enlargeA) {
            assignEntry(*sibling, chosenIndex);
        } else {
            float areaA = area(rectA);
            float areaB = area(rectB);
            if (areaA < areaB) {
                assignEntry(node, chosenIndex);
            } else if (areaB < areaA) {
                assignEntry(*sibling, chosenIndex);
            } else if (node.entries.size() <= sibling->entries.size()) {
                assignEntry(node, chosenIndex);
            } else {
                assignEntry(*sibling, chosenIndex);
            }
        }
        remaining--;
    }

    return sibling;
}

size_t EmissionCellRTree::chooseSubtree(const Node &node, const Rect &rect) {
    size_t bestIndex = 0;
    float bestEnlargement = std::numeric_limits<float>::max();
    float bestArea = std::numeric_limits<float>::max();

    for (size_t i = 0; i < node.entries.size(); i++) {
        float currentEnlargement = enlargement(node.entries[i].rect, rect);
        float currentArea = area(node.entries[i].rect);
        if (currentEnlargement < bestEnlargement ||
            (currentEnlargement == bestEnlargement && currentArea < bestArea)) {
            bestIndex = i;
            bestEnlargement = currentEnlargement;
            bestArea = currentArea;
        }
    }

    return bestIndex;
}

bool EmissionCellRTree::removeRecursive(Node &node, const Rect &rect, const Value &value, EntryList &reinserts) {
    if (node.leaf) {
        for (auto it = node.entries.begin(); it != node.entries.end(); ++it) {
            if (it->value == value) {
                node.entries.erase(it);
                return true;
            }
        }
        return false;
    }

    for (auto it = node.entries.begin(); it != node.entries.end(); ++it) {
        if (!intersects(it->rect, rect)) {
            continue;
        }

        if (!removeRecursive(*it->child, rect, value, reinserts)) {
            continue;
        }

        if (it->child->entries.size() < kMinEntries) {
            collectLeafEntries(*it->child, reinserts);
            node.entries.erase(it);
        } else {
            it->rect = computeNodeRect(*it->child);
        }
        return true;
    }

    return false;
}

void EmissionCellRTree::searchRecursive(const Node &node,
                                        const Rect &rect,
                                        std::vector<std::shared_ptr<const EmissionCell>> &out) {
    if (node.leaf) {
        for (const auto &entry : node.entries) {
            if (intersects(entry.rect, rect) && entry.value != nullptr) {
                out.push_back(entry.value);
            }
        }
        return;
    }

    for (const auto &entry : node.entries) {
        if (entry.child != nullptr && intersects(entry.rect, rect)) {
            searchRecursive(*entry.child, rect, out);
        }
    }
}

void EmissionCellRTree::insertEntry(Entry entry) {
    if (root_ == nullptr) {
        root_ = std::make_unique<Node>();
        root_->leaf = true;
        root_->entries.push_back(std::move(entry));
        return;
    }

    std::unique_ptr<Node> splitNodeOut;
    insertRecursive(*root_, std::move(entry), splitNodeOut);
    if (splitNodeOut == nullptr) {
        return;
    }

    auto newRoot = std::make_unique<Node>();
    newRoot->leaf = false;
    newRoot->entries.push_back(makeChildEntry(std::move(root_)));
    newRoot->entries.push_back(makeChildEntry(std::move(splitNodeOut)));
    root_ = std::move(newRoot);
}

void EmissionCellRTree::insertRecursive(Node &node, Entry entry, std::unique_ptr<Node> &splitNodeOut) {
    if (node.leaf) {
        node.entries.push_back(std::move(entry));
        if (node.entries.size() > kMaxEntries) {
            splitNodeOut = splitNode(node);
        }
        return;
    }

    size_t childIndex = chooseSubtree(node, entry.rect);
    std::unique_ptr<Node> childSplit;
    insertRecursive(*node.entries[childIndex].child, std::move(entry), childSplit);
    node.entries[childIndex].rect = computeNodeRect(*node.entries[childIndex].child);

    if (childSplit != nullptr) {
        node.entries.push_back(makeChildEntry(std::move(childSplit)));
        if (node.entries.size() > kMaxEntries) {
            splitNodeOut = splitNode(node);
        }
    }
}

void EmissionCellRTree::insert(const Rect &rect, const Value &value) {
    insertEntry(makeLeafEntry(rect, value));
}

bool EmissionCellRTree::remove(const Rect &rect, const Value &value) {
    if (root_ == nullptr) {
        return false;
    }

    EntryList reinserts;
    bool removed = removeRecursive(*root_, rect, value, reinserts);
    if (!removed) {
        return false;
    }

    if (root_->entries.empty()) {
        root_.reset();
    } else if (!root_->leaf && root_->entries.size() == 1) {
        root_ = std::move(root_->entries[0].child);
    }

    for (auto &entry : reinserts) {
        insertEntry(std::move(entry));
    }
    return true;
}

void EmissionCellRTree::search(const Rect &rect, std::vector<std::shared_ptr<const EmissionCell>> &out) const {
    out.clear();
    if (root_ == nullptr) {
        return;
    }
    searchRecursive(*root_, rect, out);
}

Emission::Emission(std::weak_ptr<Textures> textures) : textures_(std::move(textures)) {}

uint64_t Emission::hashCombine64(uint64_t seed, uint64_t value) {
    seed ^= value + 0x9e3779b97f4a7c15ull + (seed << 6) + (seed >> 2);
    return seed;
}

EmissionCellRTree::Rect Emission::buildRect(const EmissionCell &cell) {
    return buildRect(cell.uvMin, cell.uvMax);
}

EmissionCellRTree::Rect Emission::buildRect(const glm::vec2 &uvMin, const glm::vec2 &uvMax) {
    return EmissionCellRTree::Rect{
        .minX = std::min(uvMin.x, uvMax.x),
        .minY = std::min(uvMin.y, uvMax.y),
        .maxX = std::max(uvMin.x, uvMax.x),
        .maxY = std::max(uvMin.y, uvMax.y),
    };
}

void Emission::clearTextureState(TextureState &state) {
    state.tiles.clear();
    state.tree = nullptr;
    state.version++;
}

void Emission::reset() {
    std::unique_lock lock(mtx_);
    for (auto &state : texturesState_) {
        clearTextureState(state);
    }
}

void Emission::resetTexture(uint32_t textureID) {
    if (textureID >= texturesState_.size()) {
        return;
    }

    std::unique_lock lock(mtx_);
    clearTextureState(texturesState_[textureID]);
}

void Emission::updateTile(uint32_t textureID, uint64_t tileKey, const EmissionCellUpload *cells, int cellCount) {
    if (textureID >= texturesState_.size()) {
        return;
    }

    std::unique_lock lock(mtx_);
    auto &state = texturesState_[textureID];
    if (state.tree == nullptr) {
        state.tree = std::make_unique<EmissionCellRTree>();
    }

    auto existingIter = state.tiles.find(tileKey);
    if (existingIter != state.tiles.end()) {
        for (const auto &cell : existingIter->second) {
            if (cell == nullptr) {
                continue;
            }
            state.tree->remove(buildRect(*cell), cell);
        }
        state.tiles.erase(existingIter);
    }

    std::vector<std::shared_ptr<EmissionCell>> newCells;
    if (cells != nullptr && cellCount > 0) {
        newCells.reserve(static_cast<size_t>(cellCount));
        for (int cellIndex = 0; cellIndex < cellCount; cellIndex++) {
            const EmissionCellUpload &upload = cells[cellIndex];
            auto cell = std::make_shared<EmissionCell>();
            cell->uvMin = glm::vec2(upload.u0, upload.v0);
            cell->uvMax = glm::vec2(upload.u1, upload.v1);
            cell->avgEmission = std::max(upload.avgEmission, 0.0f);
            cell->avgColor = glm::vec3(upload.avgR, upload.avgG, upload.avgB);
            cell->stableKey = buildCellStableKey(tileKey, static_cast<uint32_t>(cellIndex));

            state.tree->insert(buildRect(*cell), cell);
            newCells.push_back(std::move(cell));
        }
    }

    if (!newCells.empty()) {
        state.tiles.emplace(tileKey, std::move(newCells));
    }
    state.version++;
}

void Emission::collectCells(uint32_t textureID,
                            const glm::vec2 &uvMin,
                            const glm::vec2 &uvMax,
                            std::vector<std::shared_ptr<const EmissionCell>> &out) const {
    out.clear();
    if (textureID >= texturesState_.size()) {
        return;
    }

    std::shared_lock lock(mtx_);
    const auto &state = texturesState_[textureID];
    if (state.tree == nullptr) {
        return;
    }

    state.tree->search(buildRect(uvMin, uvMax), out);
}
