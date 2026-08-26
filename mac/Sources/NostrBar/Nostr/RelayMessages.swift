import Foundation

/// Parsed client-relevant relay messages (NIP-01).
enum RelayMessage: Equatable {
    /// `["OK", <event_id>, <true|false>, <message>]`
    case ok(eventID: String, accepted: Bool, message: String)
    /// `["NOTICE", <message>]`
    case notice(String)
    /// Anything else (EVENT/EOSE/CLOSED) that this publisher ignores.
    case ignored

    static func parse(_ data: Data) -> RelayMessage? {
        guard let array = try? JSONSerialization.jsonObject(with: data) as? [Any],
              let type = array.first as? String
        else { return nil }

        switch type {
        case "OK":
            guard array.count >= 3,
                  let id = array[1] as? String,
                  let accepted = array[2] as? Bool
            else { return nil }
            let message = (array.count > 3 ? array[3] as? String : "") ?? ""
            return .ok(eventID: id.lowercased(), accepted: accepted, message: message)
        case "NOTICE":
            return .notice(array.count > 1 ? (array[1] as? String ?? "") : "")
        default:
            return .ignored
        }
    }
}

