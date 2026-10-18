#!/usr/bin/env bash
#
# Knot PKI Manager
# Interactive CA and peer certificate manager.
#
# Requirements:
#   - Bash 3.2+
#   - OpenSSL 1.1.1+ (OpenSSL 3.x recommended)
#
# Usage:
#   chmod +x knot-pki.sh
#   ./knot-pki.sh
#
# Optional:
#   KNOT_PKI_DIR="$HOME/knot-pki" ./knot-pki.sh

set -u
umask 077

APP_NAME="Knot PKI Manager"
OUT_DIR="${KNOT_PKI_DIR:-./knot-pki}"
TMP_DIR=""

# ------------------------------------------------------------
# Terminal colors
# ------------------------------------------------------------

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
    C_RESET="$(printf '\033[0m')"
    C_BOLD="$(printf '\033[1m')"
    C_DIM="$(printf '\033[2m')"
    C_RED="$(printf '\033[31m')"
    C_GREEN="$(printf '\033[32m')"
    C_YELLOW="$(printf '\033[33m')"
    C_CYAN="$(printf '\033[36m')"
else
    C_RESET=""
    C_BOLD=""
    C_DIM=""
    C_RED=""
    C_GREEN=""
    C_YELLOW=""
    C_CYAN=""
fi

# ------------------------------------------------------------
# General helpers
# ------------------------------------------------------------

line() {
    printf '%s\n' "------------------------------------------------------------"
}

header() {
    printf '\033[2J\033[H'
    printf '%s%s%s\n' "$C_BOLD" "$APP_NAME" "$C_RESET"
    line
    printf '%s\n\n' "$1"
}

ok() {
    printf '%s[OK]%s %s\n' "$C_GREEN" "$C_RESET" "$1"
}

warn() {
    printf '%s[!]%s %s\n' "$C_YELLOW" "$C_RESET" "$1"
}

fail() {
    printf '%s[ERROR]%s %s\n' "$C_RED" "$C_RESET" "$1" >&2
}

pause() {
    printf '\n%sPress Enter to continue...%s' "$C_DIM" "$C_RESET"
    IFS= read -r _ || true
}

cleanup() {
    if [ -n "$TMP_DIR" ] && [ -d "$TMP_DIR" ]; then
        rm -rf "$TMP_DIR"
    fi
    TMP_DIR=""
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' HUP TERM

make_temp_dir() {
    TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/knot-pki.XXXXXXXX")" || return 1
    chmod 700 "$TMP_DIR"
}

# ------------------------------------------------------------
# Arrow-key menu
#
# Result is stored in MENU_CHOICE (0-based index).
# Returns 1 if cancelled with Esc.
# MENU_PREFACE may name a function to print above the menu.
# ------------------------------------------------------------

MENU_CHOICE=0
MENU_PREFACE=""

menu() {
    title="$1"
    shift

    selected=0
    count=$#

    while :; do
        printf '\033[2J\033[H'
        printf '%s%s%s\n' "$C_BOLD" "$APP_NAME" "$C_RESET"
        line
        if [ -n "$MENU_PREFACE" ]; then
            "$MENU_PREFACE"
        fi
        printf '%s%s%s\n\n' "$C_BOLD" "$title" "$C_RESET"

        index=0
        for item in "$@"; do
            if [ "$index" -eq "$selected" ]; then
                printf '  %s%s> %s%s\n' "$C_CYAN" "$C_BOLD" "$item" "$C_RESET"
            else
                printf '    %s\n' "$item"
            fi
            index=$((index + 1))
        done

        printf '\n%s↑/↓ (or j/k) Navigate   Enter Select   Esc Back%s\n' \
            "$C_DIM" "$C_RESET"

        key=""
        IFS= read -r -s -n 1 key || return 1

        case "$key" in
            "")
                MENU_CHOICE="$selected"
                return 0
                ;;
            j)
                if [ "$selected" -lt $((count - 1)) ]; then
                    selected=$((selected + 1))
                fi
                ;;
            k)
                if [ "$selected" -gt 0 ]; then
                    selected=$((selected - 1))
                fi
                ;;
            "$(printf '\033')")
                rest=""
                IFS= read -r -s -n 1 -t 1 rest || true

                if [ "$rest" = "[" ] || [ "$rest" = "O" ]; then
                    direction=""
                    IFS= read -r -s -n 1 -t 1 direction || true

                    case "$direction" in
                        A)
                            if [ "$selected" -gt 0 ]; then
                                selected=$((selected - 1))
                            fi
                            ;;
                        B)
                            if [ "$selected" -lt $((count - 1)) ]; then
                                selected=$((selected + 1))
                            fi
                            ;;
                    esac
                else
                    return 1
                fi
                ;;
        esac
    done
}

# ------------------------------------------------------------
# Prompts
# ------------------------------------------------------------

# Quiet yes/no question. Default is No.
yesno() {
    printf '\n%s [y/N]: ' "$1"

    answer=""
    IFS= read -r answer || return 1

    case "$answer" in
        y|Y|yes|YES|Yes) return 0 ;;
        *) return 1 ;;
    esac
}

# Confirmation with a title and message; prints "cancelled" on No.
confirm() {
    title="$1"
    message="$2"

    printf '\n%s%s%s\n\n' "$C_BOLD" "$title" "$C_RESET"
    printf '%b\n\n' "$message"
    printf 'Continue? [y/N]: '

    answer=""
    IFS= read -r answer || return 1

    case "$answer" in
        y|Y|yes|YES|Yes)
            return 0
            ;;
        *)
            printf '\nOperation cancelled.\n'
            return 1
            ;;
    esac
}

# ------------------------------------------------------------
# Password input
# ------------------------------------------------------------

SECRET_VALUE=""

read_secret() {
    title="$1"
    prompt="$2"

    while :; do
        printf '\n%s%s%s\n' "$C_BOLD" "$title" "$C_RESET"
        printf '%s\n' "$prompt"

        printf 'Password: '
        first=""
        IFS= read -r -s first || return 1
        printf '\n'

        printf 'Confirm:  '
        second=""
        IFS= read -r -s second || return 1
        printf '\n'

        if [ -z "$first" ]; then
            warn "The password must not be empty."
        elif [ "$first" != "$second" ]; then
            warn "The passwords do not match."
        else
            SECRET_VALUE="$first"
            unset first second
            return 0
        fi

        unset first second
    done
}

save_secret() {
    secret_path="$1"

    printf '%s' "$SECRET_VALUE" > "$secret_path"
    chmod 600 "$secret_path"
    SECRET_VALUE=""
}

# Ask for a single existing password (no confirmation).
read_existing_secret() {
    printf '\n%s%s%s\n' "$C_BOLD" "$1" "$C_RESET"
    printf '%s\n' "$2"
    printf 'Password: '

    pw=""
    IFS= read -r -s pw || return 1
    printf '\n'

    SECRET_VALUE="$pw"
    unset pw
}

# ------------------------------------------------------------
# Private-key password configuration
# ------------------------------------------------------------

KEY_ENCRYPTED=0

configure_key_password() {
    label="$1"
    password_file="$2"

    if yesno "Protect the $label private key with a password?"; then
        read_secret "$label Key Password" \
            "Choose a strong password for this private key." || return 1

        save_secret "$password_file"
        KEY_ENCRYPTED=1
    else
        : > "$password_file"
        chmod 600 "$password_file"
        KEY_ENCRYPTED=0
        warn "The private key will not be encrypted."
    fi
}

gen_ec_key() {
    out_file="$1"
    pass_file="$2"

    openssl ecparam -name prime256v1 -genkey -noout \
        -param_enc named_curve \
        -out "$TMP_DIR/raw.key" || return 1

    if [ "$KEY_ENCRYPTED" -eq 1 ]; then
        openssl pkcs8 -topk8 -v2 aes-256-cbc \
            -in "$TMP_DIR/raw.key" \
            -passout "file:$pass_file" \
            -out "$out_file" || {
                rm -f "$TMP_DIR/raw.key"
                return 1
            }
    else
        openssl pkcs8 -topk8 -nocrypt \
            -in "$TMP_DIR/raw.key" \
            -out "$out_file" || {
                rm -f "$TMP_DIR/raw.key"
                return 1
            }
    fi

    rm -f "$TMP_DIR/raw.key"
}

# ------------------------------------------------------------
# Directory and status
# ------------------------------------------------------------

ca_exists() {
    [ -f "$OUT_DIR/ca.crt" ] && [ -f "$OUT_DIR/ca.key" ]
}

key_is_encrypted() {
    grep -q 'ENCRYPTED' "$1" 2>/dev/null
}

peer_count() {
    count=0

    if [ -d "$OUT_DIR" ]; then
        for file in "$OUT_DIR"/peer-*.crt; do
            [ -f "$file" ] || continue
            count=$((count + 1))
        done
    fi

    printf '%s' "$count"
}

ensure_output_directory() {
    if [ -d "$OUT_DIR" ]; then
        return 0
    fi

    if ! confirm "Create Output Directory" \
        "Create this directory?\n\n$OUT_DIR"; then
        return 1
    fi

    mkdir -p "$OUT_DIR" || {
        fail "Could not create $OUT_DIR"
        return 1
    }
    chmod 700 "$OUT_DIR"
}

show_status() {
    printf 'Directory : %s\n' "$OUT_DIR"

    if ca_exists; then
        printf 'CA        : %sAvailable%s\n' "$C_GREEN" "$C_RESET"
    else
        printf 'CA        : %sNot configured%s\n' "$C_RED" "$C_RESET"
    fi

    printf 'Peer certs: %s\n' "$(peer_count)"
    printf '\n'
}

# ------------------------------------------------------------
# Create a new CA
# ------------------------------------------------------------

generate_ca() {
    header "Create Certificate Authority"

    if ca_exists; then
        warn "An existing CA was found."

        if ! confirm "Replace Existing CA" \
            "This replaces the CA private key and certificate.\n\nExisting peer certificates will no longer chain to the new CA. All peers must be reissued and their trust configuration updated."; then
            return 0
        fi
    else
        if ! confirm "Create Certificate Authority" \
            "A new CA will be created in:\n\n$OUT_DIR\n\nThe CA certificate will be valid for 10 years."; then
            return 0
        fi
    fi

    ensure_output_directory || return 0
    make_temp_dir || {
        fail "Could not create a temporary directory."
        return 1
    }

    if ! configure_key_password "CA" "$TMP_DIR/ca.pass"; then
        cleanup
        return 0
    fi

    if [ "$KEY_ENCRYPTED" -eq 1 ]; then
        key_input_args=(-passin "file:$TMP_DIR/ca.pass")
    else
        key_input_args=()
    fi

    gen_ec_key "$TMP_DIR/ca.key" "$TMP_DIR/ca.pass" || {
        fail "CA key generation failed."
        cleanup
        return 1
    }

    cat > "$TMP_DIR/ca.cnf" <<'EOF'
[req]
distinguished_name = dn
prompt = no
x509_extensions = v3_ca

[dn]
CN = Knot Local CA

[v3_ca]
basicConstraints = critical,CA:TRUE,pathlen:0
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
EOF

    openssl req -x509 -new \
        -config "$TMP_DIR/ca.cnf" \
        -key "$TMP_DIR/ca.key" \
        ${key_input_args[@]+"${key_input_args[@]}"} \
        -sha256 \
        -days 3650 \
        -out "$TMP_DIR/ca.crt" || {
            fail "CA certificate generation failed."
            cleanup
            return 1
        }

    printf '\n%sFiles to be written:%s\n' "$C_BOLD" "$C_RESET"
    printf '  %s/ca.key\n' "$OUT_DIR"
    printf '  %s/ca.crt\n' "$OUT_DIR"

    if ! confirm "Write CA Files" \
        "Write the generated CA key and certificate to disk?"; then
        cleanup
        return 0
    fi

    install -m 600 "$TMP_DIR/ca.key" "$OUT_DIR/ca.key" || {
        fail "Could not write ca.key"
        cleanup
        return 1
    }
    install -m 644 "$TMP_DIR/ca.crt" "$OUT_DIR/ca.crt" || {
        fail "Could not write ca.crt"
        cleanup
        return 1
    }

    rm -f "$OUT_DIR/ca.srl"
    cleanup

    printf '\n'
    ok "Certificate Authority created."
    printf '\nCA certificate: %s/ca.crt\n' "$OUT_DIR"
    printf 'CA private key: %s/ca.key\n' "$OUT_DIR"
    warn "Keep a secure backup of the CA private key."
}

# ------------------------------------------------------------
# Create a peer identity
# ------------------------------------------------------------

generate_peer() {
    header "Create Peer Identity"

    if ! ca_exists; then
        warn "No CA was found. Create a CA first."
        return 0
    fi

    if ! confirm "Create Peer Identity" \
        "Generate a random 128-bit PeerId, an EC private key, a CA-signed X.509 certificate, and a PKCS#12 keystore."; then
        return 0
    fi

    ensure_output_directory || return 0
    make_temp_dir || {
        fail "Could not create a temporary directory."
        return 1
    }

    if ! configure_key_password "Peer" "$TMP_DIR/peer.pass"; then
        cleanup
        return 0
    fi

    if ! read_secret "PKCS#12 Keystore Password" \
        "Choose a password for the .p12 keystore."; then
        cleanup
        return 0
    fi
    save_secret "$TMP_DIR/p12.pass"

    while :; do
        peer_id="$(openssl rand -hex 16)" || {
            fail "Could not generate a PeerId."
            cleanup
            return 1
        }
        prefix="$OUT_DIR/peer-$peer_id"

        if [ ! -e "$prefix.key" ] &&
           [ ! -e "$prefix.crt" ] &&
           [ ! -e "$prefix.p12" ] &&
           [ ! -e "$prefix.id" ]; then
            break
        fi
    done

    if [ "$KEY_ENCRYPTED" -eq 1 ]; then
        key_input_args=(-passin "file:$TMP_DIR/peer.pass")
    else
        key_input_args=()
    fi

    gen_ec_key "$TMP_DIR/peer.key" "$TMP_DIR/peer.pass" || {
        fail "Peer key generation failed."
        cleanup
        return 1
    }

    cat > "$TMP_DIR/peer-req.cnf" <<EOF
[req]
distinguished_name = dn
prompt = no

[dn]
CN = Knot Peer $peer_id
EOF

    openssl req -new \
        -config "$TMP_DIR/peer-req.cnf" \
        -key "$TMP_DIR/peer.key" \
        ${key_input_args[@]+"${key_input_args[@]}"} \
        -out "$TMP_DIR/peer.csr" || {
            fail "Certificate request generation failed."
            cleanup
            return 1
        }

    cat > "$TMP_DIR/extensions.cnf" <<EOF
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature
extendedKeyUsage = clientAuth,serverAuth
subjectAltName = URI:urn:knot:peer:$peer_id
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid
EOF

    if ! confirm "Sign Peer Certificate" \
        "The CA private key will sign the certificate for:\n\nPeerId: $peer_id"; then
        cleanup
        return 0
    fi

    if key_is_encrypted "$OUT_DIR/ca.key"; then
        if ! read_existing_secret "CA Key Password" \
            "Enter the password used when creating the CA."; then
            cleanup
            return 0
        fi
        save_secret "$TMP_DIR/ca.pass"
        ca_input_args=(-passin "file:$TMP_DIR/ca.pass")
    else
        ca_input_args=()
    fi

    openssl x509 -req \
        -in "$TMP_DIR/peer.csr" \
        -CA "$OUT_DIR/ca.crt" \
        -CAkey "$OUT_DIR/ca.key" \
        ${ca_input_args[@]+"${ca_input_args[@]}"} \
        -CAserial "$OUT_DIR/ca.srl" \
        -CAcreateserial \
        -days 825 \
        -sha256 \
        -extfile "$TMP_DIR/extensions.cnf" \
        -out "$TMP_DIR/peer.crt" || {
            fail "Certificate signing failed (wrong CA password?)."
            cleanup
            return 1
        }

    openssl pkcs12 -export \
        -name "knot-peer-$peer_id" \
        -inkey "$TMP_DIR/peer.key" \
        ${key_input_args[@]+"${key_input_args[@]}"} \
        -in "$TMP_DIR/peer.crt" \
        -certfile "$OUT_DIR/ca.crt" \
        -passout "file:$TMP_DIR/p12.pass" \
        -out "$TMP_DIR/peer.p12" || {
            fail "PKCS#12 generation failed."
            cleanup
            return 1
        }

    printf '%s\n' "$peer_id" > "$TMP_DIR/peer.id"

    printf '\n%sFiles to be written:%s\n' "$C_BOLD" "$C_RESET"
    printf '  %s.key\n' "$prefix"
    printf '  %s.crt\n' "$prefix"
    printf '  %s.p12\n' "$prefix"
    printf '  %s.id\n' "$prefix"

    if ! confirm "Write Peer Files" \
        "Write the peer's private key, certificate, keystore, and PeerId file to disk?"; then
        cleanup
        return 0
    fi

    install -m 600 "$TMP_DIR/peer.key" "$prefix.key" &&
    install -m 644 "$TMP_DIR/peer.crt" "$prefix.crt" &&
    install -m 600 "$TMP_DIR/peer.p12" "$prefix.p12" &&
    install -m 600 "$TMP_DIR/peer.id"  "$prefix.id" || {
        fail "Could not write peer files."
        cleanup
        return 1
    }

    cleanup

    printf '\n'
    ok "Peer identity created."
    printf '\n'
    printf 'PeerId     : %s\n' "$peer_id"
    printf 'Certificate: %s.crt\n' "$prefix"
    printf 'Keystore   : %s.p12\n' "$prefix"
    printf 'Private key: %s.key\n' "$prefix"
    printf 'Identity   : %s.id\n' "$prefix"
    printf '\n'
    warn "Passwords are not saved. Store them securely."
}

# ------------------------------------------------------------
# Inspect existing files
# ------------------------------------------------------------

inspect_files() {
    header "Existing PKI Files"

    if [ ! -d "$OUT_DIR" ]; then
        printf 'Directory does not exist:\n%s\n' "$OUT_DIR"
        pause
        return 0
    fi

    printf 'Directory: %s\n\n' "$OUT_DIR"

    found=0
    for file in "$OUT_DIR"/*; do
        [ -e "$file" ] || continue
        found=1
        printf '  %s\n' "$(basename "$file")"

        case "$file" in
            *.crt)
                subject="$(openssl x509 -in "$file" -noout -subject 2>/dev/null)" || subject=""
                expiry="$(openssl x509 -in "$file" -noout -enddate 2>/dev/null)" || expiry=""
                [ -n "$subject" ] && printf '      %s%s%s\n' "$C_DIM" "$subject" "$C_RESET"
                [ -n "$expiry" ]  && printf '      %s%s%s\n' "$C_DIM" "$expiry" "$C_RESET"
                ;;
        esac
    done

    if [ "$found" -eq 0 ]; then
        printf '  (empty)\n'
    fi

    pause
}

# ------------------------------------------------------------
# Change output directory
# ------------------------------------------------------------

change_directory() {
    header "Output Directory"

    printf 'Current directory:\n%s\n\n' "$OUT_DIR"
    printf 'Enter a new directory path (empty to cancel): '

    new_dir=""
    IFS= read -r new_dir || return 0

    [ -n "$new_dir" ] || return 0

    case "$new_dir" in
        "~")   new_dir="$HOME" ;;
        "~/"*) new_dir="$HOME/${new_dir#"~/"}" ;;
    esac

    if confirm "Change Output Directory" \
        "Use this directory for future operations?\n\n$new_dir"; then
        OUT_DIR="$new_dir"
        ok "Output directory changed."
    fi
    pause
}

# ------------------------------------------------------------
# Main menu
# ------------------------------------------------------------

main() {
    if ! command -v openssl >/dev/null 2>&1; then
        fail "OpenSSL is required but was not found."
        exit 1
    fi

    if [ ! -t 0 ] || [ ! -t 1 ]; then
        fail "Run this script directly in an interactive terminal."
        exit 1
    fi

    MENU_PREFACE="show_status"

    while :; do
        if ! menu "Main Menu — Secure local certificate management" \
            "Create a new Certificate Authority" \
            "Create a new Peer Identity" \
            "Inspect existing files" \
            "Change output directory" \
            "Exit"; then
            continue
        fi

        case "$MENU_CHOICE" in
            0) generate_ca;   pause ;;
            1) generate_peer; pause ;;
            2) inspect_files ;;
            3) change_directory ;;
            4)
                header "Exit"
                if confirm "Exit" "Exit $APP_NAME?"; then
                    break
                fi
                ;;
        esac
    done

    printf '\nGoodbye.\n'
}

main "$@"